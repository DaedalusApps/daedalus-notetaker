package com.daedalus.notes.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalus.notes.ai.ChatTurn
import com.daedalus.notes.ai.LocalLlmService
import com.daedalus.notes.ai.OFFLINE_GUARDRAIL
import com.daedalus.notes.ai.Role
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

private val SESSION_FILENAME_REGEX = Regex("""conv_(\d{14})\.md""")
private val TURN_HEADER_REGEX = Regex("""^\*\*(Me|Agent)\*\* \((\d{2}):(\d{2})\):$""")

class ConversationViewModel @JvmOverloads constructor(
    application: Application,
    private val llm: LocalLlmService = LocalLlmService.getInstance(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** File backing the current session; exposed internally so tests can assert on its content. */
    internal val sessionFile: File

    init {
        val dir = conversationsDir(application)
        val existing = findTodaysSessionFile(dir)
        if (existing != null) {
            sessionFile = existing
            _messages.value = parseSessionFile(existing)
        } else {
            val name = "conv_${SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(clock()))}.md"
            sessionFile = File(dir, name)
        }
    }

    fun clearError() { _error.value = null }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        viewModelScope.launch {
            val userMessage = ChatMessage(Role.USER, trimmed, clock())
            _messages.value = _messages.value + userMessage
            appendToFile(userMessage)

            _error.value = null
            _isGenerating.value = true
            try {
                llm.ensureLoaded()
                val turns = _messages.value.map { ChatTurn(it.role, it.text) }
                val reply = llm.generate(IDEATION_SYSTEM_PROMPT, turns)
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
        return dir.listFiles { f -> SESSION_FILENAME_REGEX.matches(f.name) }
            ?.filter { f -> SESSION_FILENAME_REGEX.find(f.name)!!.groupValues[1].take(8) == today }
            ?.maxByOrNull { it.name }
    }

    /** Parses this ViewModel's own markdown session format back into messages (see [appendToFile]). */
    private fun parseSessionFile(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        val messages = mutableListOf<ChatMessage>()
        var currentRole: Role? = null
        var currentTime: String? = null
        val text = StringBuilder()

        fun flush() {
            val role = currentRole ?: return
            val time = currentTime ?: return
            messages.add(ChatMessage(role, text.toString().trim(), reconstructMillis(time)))
            text.setLength(0)
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
}
