package com.daedalus.notes.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalus.notes.ai.ChatTurn
import com.daedalus.notes.ai.EmbeddingService
import com.daedalus.notes.ai.LocalLlmService
import com.daedalus.notes.ai.OFFLINE_GUARDRAIL
import com.daedalus.notes.ai.Role
import com.daedalus.notes.ai.aiTextBudget
import com.daedalus.notes.ai.analyzeTranscript
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.data.model.Recording
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A single chat turn in a conversation session. */
data class ChatMessage(val role: Role, val text: String, val timestampMillis: Long)

private const val IDEATION_SYSTEM_PROMPT = "You are a thoughtful ideation partner in a live " +
    "conversation with the user, like a working session with a colleague. Be concise, help " +
    "develop their thinking, and ask good clarifying follow-up questions rather than lecturing." +
    "\n\n" + OFFLINE_GUARDRAIL

private const val SUMMARY_PROMPT = "Summarize this conversation so far as a concise rolling " +
    "summary, capturing key decisions, ideas, and open threads. Write it as prose (no bullet " +
    "points) so it can be folded into a system prompt. Keep it under 200 words.\n\n" +
    OFFLINE_GUARDRAIL

// aiTextBudget() already derives its char budget from the model's 4096-token context minus
// prompt/output headroom (see AI_TEXT_BUDGET_DEFAULT in Categories.kt). Conversation turns
// accumulate without bound, so reuse that budget rather than a fresh token calculation, but keep
// only a fraction of it as extra safety margin beyond the reply headroom already baked in: at the
// default 12,000-char budget this leaves ~9,000 live-context chars (~2,250 tokens), well clear of
// the model's 4096-token ceiling once the ~800-token reply headroom is spent.
private const val CONVERSATION_CONTEXT_FRACTION = 0.75

// Keeps the last two exchanges (user + model turns) intact in the live context on rollover.
private const val TAIL_MESSAGE_COUNT = 4

// Hard ceiling on the injected summary, as a fraction of the context budget. The model is asked
// for a short summary but its output length is not guaranteed, and each rollover feeds the
// previous summary back in, so without a clamp a compounding summary could grow the real sent
// context past the budget the trip-check assumes.
private const val SUMMARY_BUDGET_FRACTION = 0.25

private val SESSION_FILENAME_REGEX = Regex("""conv_(\d{14})\.md""")
private val TURN_HEADER_REGEX = Regex("""^\*\*(Me|Agent)\*\* \((\d{2}):(\d{2})\):$""")

class ConversationViewModel @JvmOverloads constructor(
    application: Application,
    private val llm: LocalLlmService = LocalLlmService.getInstance(application),
    private val repo: RecordingRepository = RecordingRepository(AppDatabase.getInstance(application).recordingDao()),
    private val embedder: EmbeddingService = EmbeddingService(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val contextBudgetChars: Int = (aiTextBudget(application) * CONVERSATION_CONTEXT_FRACTION).toInt()
) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Rolling summary of messages already folded out of the live context, and the index into
    // _messages up to which that summary applies. The session FILE always has every turn
    // verbatim (see appendToFile) — only what gets sent to the LLM is capped.
    private var rollingSummary: String? = null
    private var summarizedThroughIndex: Int = 0

    /** File backing the current session; exposed internally so tests can assert on its content. */
    internal lateinit var sessionFile: File
        private set

    /** Locating and parsing the session file touches disk, so it runs off the main thread. */
    private val loadJob = viewModelScope.launch {
        val (file, restored) = withContext(ioDispatcher) {
            val dir = conversationsDir(application)
            val existing = findTodaysSessionFile(dir)
            if (existing != null) existing to parseSessionFile(existing) else newSessionFile(dir) to emptyList()
        }
        sessionFile = file
        _messages.value = restored
    }

    fun clearError() { _error.value = null }

    /** Rotates to a fresh session file (a new "meeting"); the previous transcript stays on disk. */
    fun startNewSession() {
        if (_isGenerating.value) return
        viewModelScope.launch {
            loadJob.join()
            sessionFile = withContext(ioDispatcher) { newSessionFile(conversationsDir(getApplication())) }
            _messages.value = emptyList()
            _error.value = null
        }
    }

    /**
     * Ends the current "meeting with the agent": converts the transcript into a [Recording]
     * through the normal save path, runs it through the same analysis pipeline a transcribed
     * local recording gets, marks the session file as ended so it is never auto-resumed, then
     * rotates to a fresh session. A no-op if the session has no messages yet.
     *
     * Analysis runs unconditionally, unlike a local audio recording, which only auto-analyzes when
     * the `auto_process` pref is on: that pref gates work kicked off automatically by capture
     * finishing, whereas tapping End is itself the explicit request for the summarized note (the
     * equivalent of the library's Analyze button).
     */
    fun endSession() {
        if (_isGenerating.value) return
        // Claimed synchronously on the caller (main) thread so a double-tap — or a send() landing
        // in the same frame — cannot slip past the guard before the coroutine body runs.
        _isGenerating.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                loadJob.join()
                val currentMessages = _messages.value
                if (currentMessages.isEmpty()) return@launch

                val filename = sessionFile.name
                val transcript = buildTranscript(currentMessages)
                repo.save(
                    Recording(
                        filename = filename,
                        transcript = transcript,
                        createdAt = clock(),
                        isLocal = true
                    )
                )
                analyzeTranscript(getApplication(), llm, embedder, repo, filename, transcript)

                // Renamed only once the work above succeeded, so a failure leaves the session
                // intact and resumable; retrying End re-saves under the same filename (the
                // primary key), which updates that row rather than adding a second one.
                val ended = withContext(ioDispatcher) {
                    val endedFile = File(sessionFile.parentFile, "${sessionFile.nameWithoutExtension}.ended.md")
                    sessionFile.renameTo(endedFile)
                }
                if (!ended) throw IOException("Could not mark ${sessionFile.name} as ended")

                sessionFile = withContext(ioDispatcher) { newSessionFile(conversationsDir(getApplication())) }
                _messages.value = emptyList()
            } catch (e: Exception) {
                Log.e("ConversationViewModel", "endSession failed", e)
                _error.value = e.message ?: "Failed to end session"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /** Renders messages as a speaker-labeled meeting transcript, e.g. "Me: ..." / "Agent: ...". */
    private fun buildTranscript(messages: List<ChatMessage>): String =
        messages.joinToString("\n\n") { message ->
            val label = if (message.role == Role.USER) "Me" else "Agent"
            "$label: ${message.text}"
        }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        // Claimed synchronously on the caller (main) thread so a rapid double-send cannot slip
        // through before the coroutine body runs.
        _isGenerating.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                loadJob.join()
                val userMessage = ChatMessage(Role.USER, trimmed, clock())
                _messages.value = _messages.value + userMessage
                appendToFile(userMessage)

                llm.ensureLoaded()
                val (systemPrompt, turns) = buildLiveContext(_messages.value)
                val reply = llm.generate(systemPrompt, turns)
                val modelMessage = ChatMessage(Role.MODEL, reply, clock())
                _messages.value = _messages.value + modelMessage
                appendToFile(modelMessage)
            } catch (e: Exception) {
                Log.e("ConversationViewModel", "Generation failed", e)
                _error.value = e.message ?: "Failed to generate a response"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /**
     * Maps messages to LLM turns, merging consecutive same-role messages: the chat template
     * requires strictly alternating roles, and a failed generation leaves two user turns in a row.
     */
    private fun toChatTurns(messages: List<ChatMessage>): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        for (message in messages) {
            val last = turns.lastOrNull()
            if (last != null && last.role == message.role) {
                turns[turns.lastIndex] = ChatTurn(message.role, last.text + "\n\n" + message.text)
            } else {
                turns.add(ChatTurn(message.role, message.text))
            }
        }
        return turns
    }

    /**
     * Builds the (systemPrompt, turns) pair to send to the LLM, capped to [contextBudgetChars].
     * While the unsummarized tail of [messages] fits the budget, it is sent in full. Once it
     * would overflow, the older portion (everything but the last [TAIL_MESSAGE_COUNT] messages)
     * is folded into a rolling summary — compounding any prior summary — which is injected into
     * the system prompt so the turn list keeps starting with USER and alternating. The summary is
     * clamped to [SUMMARY_BUDGET_FRACTION] of the budget so compounding cannot grow it without
     * bound. If the summarize call fails, this falls back to the previous summary plus the tail
     * for this send only; the rolling summary state is left untouched so the next rollover
     * retries it.
     */
    private suspend fun buildLiveContext(messages: List<ChatMessage>): Pair<String, List<ChatTurn>> {
        fun systemPromptWith(summary: String?): String =
            if (summary == null) IDEATION_SYSTEM_PROMPT
            else "$IDEATION_SYSTEM_PROMPT\n\nSummary of the conversation so far: $summary"

        val liveMessages = messages.subList(summarizedThroughIndex, messages.size)
        val systemPrompt = systemPromptWith(rollingSummary)
        val liveTurns = toChatTurns(liveMessages)
        val contextChars = systemPrompt.length + liveTurns.sumOf { it.text.length }

        // Nothing to gain from rolling over if the entire unsummarized region is already just
        // the tail — there is no older portion left to summarize away.
        if (contextChars <= contextBudgetChars || liveMessages.size <= TAIL_MESSAGE_COUNT) {
            return systemPrompt to liveTurns
        }

        // buildGemmaPrompt only folds the system prompt — and with it the injected summary — into
        // a LEADING USER turn, so the tail must start on one. A complete history is odd-length at
        // this point (the just-sent user turn is last), which puts a MODEL turn at the raw tail
        // start; that turn is pushed into the summarized span instead, so nothing is skipped.
        var tailStart = messages.size - TAIL_MESSAGE_COUNT
        while (tailStart < messages.size - 1 && messages[tailStart].role != Role.USER) tailStart++
        val olderMessages = messages.subList(summarizedThroughIndex, tailStart)
        val tailMessages = messages.subList(tailStart, messages.size)
        val olderText = buildString {
            rollingSummary?.let { append("Previous summary: $it\n\n") }
            olderMessages.forEach { message ->
                append(if (message.role == Role.USER) "User: " else "Assistant: ")
                append(message.text)
                append("\n\n")
            }
        }

        // A blank generation is treated as a failure: accepting it would advance
        // summarizedThroughIndex and drop the older span with nothing standing in for it.
        val newSummary = try {
            llm.generate(SUMMARY_PROMPT, olderText).trim().ifBlank { null }
        } catch (e: Exception) {
            Log.e("ConversationViewModel", "Summary generation failed, falling back to tail truncation", e)
            null
        }

        if (newSummary == null) {
            // Keep any summary earned by an earlier rollover: dropping it would discard context
            // that is already safely folded down, for no budget gain (this send is a strict
            // subset of the over-budget context measured above).
            return systemPrompt to toChatTurns(tailMessages)
        }
        val clamped = newSummary.take((contextBudgetChars * SUMMARY_BUDGET_FRACTION).toInt())
        rollingSummary = clamped
        summarizedThroughIndex = tailStart
        return systemPromptWith(clamped) to toChatTurns(tailMessages)
    }

    private suspend fun appendToFile(message: ChatMessage) {
        withContext(ioDispatcher) {
            val label = if (message.role == Role.USER) "Me" else "Agent"
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(message.timestampMillis))
            sessionFile.appendText("**$label** ($time):\n${message.text}\n\n")
        }
    }

    /** Finds the most recent conv_*.md file created today, if any, to resume an unfinished session. */
    private fun findTodaysSessionFile(dir: File): File? {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(clock()))
        return dir.listFiles()
            ?.filter { SESSION_FILENAME_REGEX.matchEntire(it.name)?.groupValues?.get(1)?.startsWith(today) == true }
            ?.maxByOrNull { it.name }
    }

    /** Builds a not-yet-taken session filename, so rotating twice never reuses an existing file. */
    private fun newSessionFile(dir: File): File {
        val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        var millis = clock()
        var file = File(dir, "conv_${format.format(Date(millis))}.md")
        while (file.exists()) {
            millis += 1000
            file = File(dir, "conv_${format.format(Date(millis))}.md")
        }
        return file
    }

    /** Parses this ViewModel's own markdown session format back into messages (see [appendToFile]). */
    private fun parseSessionFile(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        val messages = mutableListOf<ChatMessage>()
        var currentRole: Role? = null
        var currentTime: String? = null
        val text = StringBuilder()

        fun flush() {
            val role = currentRole
            val time = currentTime
            val body = text.toString().trim()
            text.setLength(0)
            // Content before the first turn header (corrupted or foreign file) is discarded,
            // as are empty turns; neither is representable as a message.
            if (role != null && time != null && body.isNotEmpty()) {
                messages.add(ChatMessage(role, body, reconstructMillis(time)))
            }
        }

        file.forEachLine { line ->
            val match = TURN_HEADER_REGEX.matchEntire(line)
            if (match != null) {
                flush()
                currentRole = if (match.groupValues[1] == "Me") Role.USER else Role.MODEL
                currentTime = "${match.groupValues[2]}:${match.groupValues[3]}"
            } else {
                text.append(line).append("\n")
            }
        }
        flush()
        return messages
    }

    /** Reconstructs an approximate timestamp (today's date + parsed HH:mm) for a reloaded turn. */
    private fun reconstructMillis(hhmm: String): Long {
        val (hour, minute) = hhmm.split(":").map { it.toInt() }
        val calendar = Calendar.getInstance().apply {
            timeInMillis = clock()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun conversationsDir(application: Application): File =
        File(application.filesDir, "conversations").also { it.mkdirs() }

    override fun onCleared() {
        super.onCleared()
        embedder.close()
    }
}
