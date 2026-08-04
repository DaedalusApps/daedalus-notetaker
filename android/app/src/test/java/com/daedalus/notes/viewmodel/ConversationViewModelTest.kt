package com.daedalus.notes.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.daedalus.notes.ai.ChatTurn
import com.daedalus.notes.ai.LocalLlmService
import com.daedalus.notes.ai.Role
import com.daedalus.notes.ai.SpeechService
import com.daedalus.notes.ai.TranscriptionService
import com.daedalus.notes.ai.VoiceInfo
import com.daedalus.notes.ai.WHISPER_DECODER_FILE
import com.daedalus.notes.ai.WHISPER_ENCODER_FILE
import com.daedalus.notes.ai.WHISPER_TOKENS_FILE
import com.daedalus.notes.ai.aiTextBudget
import com.daedalus.notes.ai.buildGemmaPrompt
import com.daedalus.notes.ai.whisperModelDir
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.recording.AudioRecorder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConversationViewModelTest {

    private lateinit var application: Application
    private val llm = mockk<LocalLlmService>(relaxed = true)
    private val repo = mockk<RecordingRepository>(relaxed = true)
    private val audioRecorder = mockk<AudioRecorder>(relaxed = true)
    private val transcriptionService = mockk<TranscriptionService>(relaxed = true)
    private val tts = mockk<SpeechService>(relaxed = true)
    // Counts how often the ViewModel asked for a speech engine, so tests can assert that a user
    // who never turns spoken replies on never pays for building one.
    private var ttsConstructions = 0
    private val testDispatcher = StandardTestDispatcher()

    private fun prefs() =
        application.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)

    // Fixed instant so filenames/day comparisons are deterministic across the test run.
    private val nowMillis = 1_700_000_000_000L

    private fun conversationsDir(): File = File(application.filesDir, "conversations")

    /** Marks the Whisper model as downloaded, so startVoiceInput proceeds. */
    private fun markWhisperReady() {
        val dir = whisperModelDir(application)
        dir.mkdirs()
        File(dir, WHISPER_ENCODER_FILE).writeText("x")
        File(dir, WHISPER_DECODER_FILE).writeText("x")
        File(dir, WHISPER_TOKENS_FILE).writeText("x")
    }

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any() as String) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        coEvery { llm.ensureLoaded() } returns Unit
        every { tts.isAvailable } returns true
        ttsConstructions = 0

        conversationsDir().deleteRecursively()
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        conversationsDir().deleteRecursively()
        prefs().edit().clear().commit()
    }

    private fun newViewModel(contextBudgetChars: Int? = null): ConversationViewModel = ConversationViewModel(
        application = application,
        llm = llm,
        repo = repo,
        ioDispatcher = testDispatcher,
        clock = { nowMillis },
        contextBudgetChars = contextBudgetChars ?: (aiTextBudget(application) * 0.75).toInt(),
        audioRecorderProvider = { audioRecorder },
        transcriptionServiceProvider = { transcriptionService },
        ttsProvider = { ttsConstructions++; tts }
    )

    // (a) send() twice produces user+model messages in order, and the session file contains
    //     all four turns, in order, after two exchanges.
    @Test
    fun send_twoExchanges_appendsMessagesAndFileHasAllFourTurnsInOrder() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returnsMany listOf(
            "Sounds interesting, tell me more.",
            "Great, here's a follow-up idea."
        )
        val vm = newViewModel()

        vm.send("I want to build a note app")
        advanceUntilIdle()
        vm.send("It should support voice")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(4, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals(Role.MODEL, messages[1].role)
        assertEquals(Role.USER, messages[2].role)
        assertEquals(Role.MODEL, messages[3].role)

        val fileContent = vm.sessionFile.readText()
        val order = listOf("**Me**", "**Agent**", "**Me**", "**Agent**")
        var lastIndex = -1
        order.forEach { marker ->
            val idx = fileContent.indexOf(marker, lastIndex + 1)
            assertTrue("expected $marker after index $lastIndex in:\n$fileContent", idx > lastIndex)
            lastIndex = idx
        }
        assertTrue(fileContent.contains("I want to build a note app"))
        assertTrue(fileContent.contains("Sounds interesting, tell me more."))
        assertTrue(fileContent.contains("It should support voice"))
        assertTrue(fileContent.contains("Great, here's a follow-up idea."))
    }

    // (b) Turns are appended incrementally: the file already has the user turn while
    //     generation is still in flight.
    @Test
    fun send_fileHasUserTurnWhileGenerationInFlight() = runTest {
        val gate = CompletableDeferred<String>()
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } coAnswers { gate.await() }
        val vm = newViewModel()

        vm.send("Thinking out loud")
        testDispatcher.scheduler.runCurrent()

        assertTrue(vm.isGenerating.value)
        val content = vm.sessionFile.readText()
        assertTrue(content.contains("**Me**"))
        assertTrue(content.contains("Thinking out loud"))
        assertFalse(content.contains("**Agent**"))

        gate.complete("ok")
        advanceUntilIdle()
    }

    // (c) LLM failure -> error state set, no MODEL message appended, user turn persisted
    //     in both the message list and the file.
    @Test
    fun send_llmThrows_setsErrorNoModelMessageUserTurnPersisted() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } throws RuntimeException("boom")
        val vm = newViewModel()

        vm.send("Will this fail?")
        advanceUntilIdle()

        assertEquals(1, vm.messages.value.size)
        assertEquals(Role.USER, vm.messages.value[0].role)
        assertNotNull(vm.error.value)
        assertFalse(vm.isGenerating.value)

        val content = vm.sessionFile.readText()
        assertTrue(content.contains("Will this fail?"))
        assertFalse(content.contains("**Agent**"))
    }

    // (d) Reload: a new ViewModel constructed while an unfinished session file from today
    //     exists restores its messages, and a subsequent send() continues appending to the
    //     SAME file, correctly, alongside the prior turns.
    @Test
    fun reload_existingTodaysSessionFile_restoresMessagesAndContinuesAppending() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "First reply"
        val vm1 = newViewModel()
        vm1.send("Original message")
        advanceUntilIdle()
        val originalFile = vm1.sessionFile
        assertTrue(originalFile.exists())

        val vm2 = newViewModel()
        advanceUntilIdle()

        assertEquals(2, vm2.messages.value.size)
        assertEquals("Original message", vm2.messages.value[0].text)
        assertEquals(Role.USER, vm2.messages.value[0].role)
        assertEquals("First reply", vm2.messages.value[1].text)
        assertEquals(Role.MODEL, vm2.messages.value[1].role)
        assertEquals(originalFile.absolutePath, vm2.sessionFile.absolutePath)

        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Second reply"
        vm2.send("Continuing")
        advanceUntilIdle()

        val content = originalFile.readText()
        assertTrue(content.contains("Original message"))
        assertTrue(content.contains("First reply"))
        assertTrue(content.contains("Continuing"))
        assertTrue(content.contains("Second reply"))
        assertEquals(4, vm2.messages.value.size)
    }

    // (e) After a failed generation the message list holds two USER turns in a row. The Gemma
    //     chat template rejects consecutive same-role turns, so they must be merged before the
    //     next generate() call — otherwise the session is permanently broken.
    @Test
    fun send_afterFailedGeneration_mergesConsecutiveUserTurns() = runTest {
        val captured = mutableListOf<List<ChatTurn>>()
        coEvery { llm.generate(any(), capture(captured)) } throws RuntimeException("boom")
        val vm = newViewModel()
        vm.send("First thought")
        advanceUntilIdle()

        coEvery { llm.generate(any(), capture(captured)) } returns "Recovered"
        vm.send("Second thought")
        advanceUntilIdle()

        val turns = captured.last()
        assertEquals(1, turns.size)
        assertEquals(Role.USER, turns[0].role)
        assertTrue(turns[0].text.contains("First thought"))
        assertTrue(turns[0].text.contains("Second thought"))
        // The merged turns must satisfy the real prompt builder's contract.
        buildGemmaPrompt("system", turns)
        assertEquals("Recovered", vm.messages.value.last().text)
        assertNull(vm.error.value)
    }

    // (f) The user can start a fresh session on the same day: rotating clears the transcript and
    //     writes to a new file, leaving the previous one intact on disk.
    @Test
    fun startNewSession_rotatesToNewFileAndClearsMessages() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Reply"
        val vm = newViewModel()
        vm.send("Morning meeting")
        advanceUntilIdle()
        val firstFile = vm.sessionFile

        vm.startNewSession()
        advanceUntilIdle()

        assertTrue(vm.messages.value.isEmpty())
        assertTrue(firstFile.absolutePath != vm.sessionFile.absolutePath)

        vm.send("Afternoon meeting")
        advanceUntilIdle()

        assertTrue(firstFile.readText().contains("Morning meeting"))
        assertFalse(firstFile.readText().contains("Afternoon meeting"))
        assertTrue(vm.sessionFile.readText().contains("Afternoon meeting"))
        assertFalse(vm.sessionFile.readText().contains("Morning meeting"))

        // The rotated-to session is the one resumed on reload (most recent file wins).
        val reloaded = newViewModel()
        advanceUntilIdle()
        assertEquals(vm.sessionFile.absolutePath, reloaded.sessionFile.absolutePath)
        assertEquals(1, reloaded.messages.value.count { it.role == Role.USER })
    }

    // (g) A malformed session file must never crash the parser: garbage preamble, multiline
    //     bodies, a user-typed line that looks like a turn header, empty turns, trailing blanks.
    @Test
    fun reload_malformedSessionFile_parsesWithoutCrashing() = runTest {
        val dir = conversationsDir().apply { mkdirs() }
        val name = "conv_" + SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(nowMillis)) + ".md"
        File(dir, name).writeText(
            """
            garbage preamble not written by us
            **Not a header**
            **Me** (09:15):
            line one
            line two

            **Agent** (09:16):
            **Me** (99:99):
            **Me** (09:17):
            quoting a header: **Me** (12:00):
            still the same message

            """.trimIndent() + "\n\n\n"
        )

        val vm = newViewModel()
        advanceUntilIdle()

        val messages = vm.messages.value
        // Preamble and the empty Agent turn are dropped; the rest survives.
        assertEquals(2, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("line one\nline two", messages[0].text)
        assertEquals(Role.USER, messages[1].role)
        assertTrue(messages[1].text.contains("still the same message"))
        assertFalse(messages.any { it.text.contains("garbage preamble") })
    }

    // (h) P5.3: while the running history stays under the context budget, the full history is
    //     sent as-is and no summarization call is made.
    @Test
    fun send_historyUnderBudget_sendsFullHistoryNoSummaryCall() = runTest {
        val summaryCalls = mutableListOf<String>()
        val replyCalls = mutableListOf<List<ChatTurn>>()
        coEvery { llm.generate(any(), capture(summaryCalls)) } returns "unused"
        coEvery { llm.generate(any(), capture(replyCalls)) } returnsMany listOf("first reply", "second reply")
        val vm = newViewModel(contextBudgetChars = 2_000)

        vm.send("short message one")
        advanceUntilIdle()
        vm.send("short message two")
        advanceUntilIdle()

        assertTrue("no summary call expected while under budget", summaryCalls.isEmpty())
        assertEquals(3, replyCalls.last().size)
    }

    // (i)+(j) P5.3: once the running history exceeds the context budget, the older portion is
    //     summarized (compounding on top of any prior summary) and only the recent tail is sent,
    //     keeping the live context within budget. The session file (full-transcript guarantee)
    //     is unaffected by rollover.
    @Test
    fun send_historyExceedsBudget_summarizesOlderTurnsCompoundsAndCapsContext() = runTest {
        val budget = 700
        val systemPromptCalls = mutableListOf<String>()
        val summaryCalls = mutableListOf<String>()
        val replyCalls = mutableListOf<List<ChatTurn>>()
        coEvery { llm.generate(capture(systemPromptCalls), capture(summaryCalls)) } returnsMany
            listOf("Rolling summary one.", "Rolling summary two.")
        coEvery { llm.generate(capture(systemPromptCalls), capture(replyCalls)) } returns "reply"
        val vm = newViewModel(contextBudgetChars = budget)
        val pad = "x".repeat(60)

        // Four exchanges: the running total crosses the budget partway through the 4th send,
        // triggering the first rollover.
        vm.send("FIRST_MARKER $pad"); advanceUntilIdle()
        vm.send("second $pad"); advanceUntilIdle()
        vm.send("third $pad"); advanceUntilIdle()
        vm.send("TAIL_MARKER $pad"); advanceUntilIdle()

        assertEquals("expected exactly one summary call at the first rollover", 1, summaryCalls.size)
        assertTrue(summaryCalls[0].contains("FIRST_MARKER"))
        assertFalse(summaryCalls[0].contains("TAIL_MARKER"))

        val firstRolloverReplyTurns = replyCalls.last()
        assertTrue(firstRolloverReplyTurns.none { it.text.contains("FIRST_MARKER") })
        assertTrue(firstRolloverReplyTurns.any { it.text.contains("TAIL_MARKER") })
        val firstRolloverSystemPrompt = systemPromptCalls[systemPromptCalls.size - 1]
        val totalContextChars = firstRolloverSystemPrompt.length +
            firstRolloverReplyTurns.sumOf { it.text.length }
        assertTrue("live context ($totalContextChars) must fit the budget ($budget)", totalContextChars <= budget)

        // The tail must still start with a USER turn: buildGemmaPrompt folds the system prompt
        // (carrying the summary) into a leading USER turn only, and silently drops it otherwise.
        assertEquals(Role.USER, firstRolloverReplyTurns.first().role)
        val prompt = buildGemmaPrompt(firstRolloverSystemPrompt, firstRolloverReplyTurns)
        assertTrue(
            "the rolling summary must actually reach the model's prompt",
            prompt.contains("Rolling summary one.")
        )

        // One more send pushes past the budget again; the second summary call must compound on
        // top of the first rolling summary.
        vm.send("fourth $pad"); advanceUntilIdle()

        assertEquals("expected a second summary call at the second rollover", 2, summaryCalls.size)
        assertTrue(
            "second summarize input must include the first rolling summary so it compounds",
            summaryCalls[1].contains("Rolling summary one.")
        )

        // The session file keeps every turn verbatim regardless of rollover.
        val fileContent = vm.sessionFile.readText()
        assertTrue(fileContent.contains("FIRST_MARKER"))
        assertTrue(fileContent.contains("TAIL_MARKER"))
        assertTrue(fileContent.contains("fourth"))
    }

    // (k) P5.3: if the summarize call throws, the send must not fail or surface an error — fall
    //     back to plain tail-truncation for that send, and retry summarizing on the next rollover.
    @Test
    fun send_summaryCallThrows_fallsBackToTailTruncationWithoutErrorAndRetriesNextTime() = runTest {
        val budget = 700
        var summaryCallCount = 0
        val replyCalls = mutableListOf<List<ChatTurn>>()
        coEvery { llm.generate(any(), any<String>()) } answers {
            summaryCallCount++
            throw RuntimeException("summary failed")
        }
        coEvery { llm.generate(any(), capture(replyCalls)) } returns "reply"
        val vm = newViewModel(contextBudgetChars = budget)
        val pad = "x".repeat(60)

        vm.send("FIRST_MARKER $pad"); advanceUntilIdle()
        vm.send("second $pad"); advanceUntilIdle()
        vm.send("third $pad"); advanceUntilIdle()
        vm.send("TAIL_MARKER $pad"); advanceUntilIdle()

        assertEquals(1, summaryCallCount)
        assertNull("summary failure must not surface as a user-visible error", vm.error.value)
        assertFalse(vm.isGenerating.value)
        val fallbackTurns = replyCalls.last()
        assertTrue(fallbackTurns.none { it.text.contains("FIRST_MARKER") })
        assertTrue(fallbackTurns.any { it.text.contains("TAIL_MARKER") })

        // Next rollover retries summarizing rather than giving up permanently.
        vm.send("fifth $pad"); advanceUntilIdle()
        assertEquals(2, summaryCallCount)
    }

    // (l) P5.3: the summarizer's output length is not guaranteed and each rollover feeds the
    //     previous summary back in, so an over-long summary must be clamped before injection —
    //     otherwise the compounding summary defeats the very cap it exists to enforce.
    @Test
    fun send_oversizedSummary_isClampedBeforeInjection() = runTest {
        val budget = 700
        val replySystemPrompts = mutableListOf<String>()
        coEvery { llm.generate(any(), any<String>()) } returns "S".repeat(5_000)
        coEvery { llm.generate(capture(replySystemPrompts), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel(contextBudgetChars = budget)
        val pad = "x".repeat(60)

        vm.send("first $pad"); advanceUntilIdle()
        vm.send("second $pad"); advanceUntilIdle()
        vm.send("third $pad"); advanceUntilIdle()
        vm.send("fourth $pad"); advanceUntilIdle()

        val injected = replySystemPrompts.last().substringAfter("so far: ")
        assertEquals(175, injected.length)
    }

    // (m) P5.3: a later summarize failure must not throw away a summary an earlier rollover
    //     already earned — falling back to the bare system prompt would lose that context for
    //     nothing, since the tail-only send is already a subset of the over-budget context.
    @Test
    fun send_summaryFailsAfterEarlierSuccess_keepsPreviousSummary() = runTest {
        val budget = 700
        val replySystemPrompts = mutableListOf<String>()
        coEvery { llm.generate(any(), any<String>()) } returns "Rolling summary one." andThenThrows
            RuntimeException("summary failed")
        coEvery { llm.generate(capture(replySystemPrompts), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel(contextBudgetChars = budget)
        val pad = "x".repeat(60)

        vm.send("first $pad"); advanceUntilIdle()
        vm.send("second $pad"); advanceUntilIdle()
        vm.send("third $pad"); advanceUntilIdle()
        vm.send("fourth $pad"); advanceUntilIdle()
        assertTrue(replySystemPrompts.last().contains("Rolling summary one."))

        vm.send("fifth $pad"); advanceUntilIdle()

        assertNull(vm.error.value)
        assertTrue(
            "the earlier rolling summary must survive a later summarize failure",
            replySystemPrompts.last().contains("Rolling summary one.")
        )
    }

    // (P5.4-a) endSession() inserts exactly one Recording whose transcript contains every turn,
    //     in order, with speaker labels, mirroring local-recording save conventions.
    @Test
    fun endSession_insertsOneRecordingWithSpeakerLabeledTranscriptInOrder() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returnsMany listOf(
            "Sounds interesting, tell me more.",
            "Great, here's a follow-up idea."
        )
        val vm = newViewModel()
        vm.send("I want to build a note app")
        advanceUntilIdle()
        vm.send("It should support voice")
        advanceUntilIdle()

        val saved = slot<Recording>()
        coEvery { repo.save(capture(saved)) } returns Unit

        vm.endSession()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.save(any()) }
        val recording = saved.captured
        assertTrue(recording.isLocal)
        val transcript = recording.transcript
        val order = listOf("Me: I want to build a note app", "Agent: Sounds interesting, tell me more.",
            "Me: It should support voice", "Agent: Great, here's a follow-up idea.")
        var lastIndex = -1
        order.forEach { marker ->
            val idx = transcript.indexOf(marker, lastIndex + 1)
            assertTrue("expected \"$marker\" after index $lastIndex in:\n$transcript", idx > lastIndex)
            lastIndex = idx
        }
    }

    // (P5.4-b) endSession() triggers the same post-save analysis pipeline a transcribed local
    //     recording gets: the LLM is invoked (mocked seam) and the resulting analysis is saved.
    @Test
    fun endSession_triggersAnalysisPipeline() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Reply"
        val vm = newViewModel()
        vm.send("Let's plan the launch")
        advanceUntilIdle()

        coEvery { llm.generate(any(), any<String>()) } returns
            """{"title":"Launch plan","shortSummary":"short","fullSummary":"full","mindMap":"","topics":["launch"]}"""

        vm.endSession()
        advanceUntilIdle()

        coVerify(atLeast = 1) { llm.generate(any(), any<String>()) }
        coVerify(exactly = 1) { repo.updateSummary(any(), any(), any(), any(), any(), any()) }
    }

    // (P5.4-c) The session file is renamed to its ended form, stays on disk, and its content is
    //     unaffected (verbatim transcript guarantee).
    @Test
    fun endSession_rendersSessionFileEndedAndKeepsContent() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Reply"
        val vm = newViewModel()
        vm.send("Original content to preserve")
        advanceUntilIdle()
        val originalFile = vm.sessionFile
        val originalContent = originalFile.readText()

        vm.endSession()
        advanceUntilIdle()

        assertFalse("original session file should be renamed away", originalFile.exists())
        val dir = conversationsDir()
        val endedFiles = dir.listFiles()?.filter { it.name.contains("ended") } ?: emptyList()
        assertEquals(1, endedFiles.size)
        assertEquals(originalContent, endedFiles[0].readText())
    }

    // (P5.4-d) After endSession, a new ViewModel does NOT resume the ended session — it starts a
    //     fresh one.
    @Test
    fun endSession_endedSessionIsNeverResumedByNewViewModel() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Reply"
        val vm = newViewModel()
        vm.send("This session should end")
        advanceUntilIdle()

        vm.endSession()
        advanceUntilIdle()

        val reloaded = newViewModel()
        advanceUntilIdle()

        assertTrue(reloaded.messages.value.isEmpty())
        assertFalse(reloaded.sessionFile.name.contains("ended"))
    }

    // (P5.4-e) An empty session (no messages) makes endSession() a no-op: no Recording inserted,
    //     no file changes.
    @Test
    fun endSession_emptySession_isNoOp() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val fileBefore = vm.sessionFile
        val existedBefore = fileBefore.exists()

        vm.endSession()
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.save(any()) }
        assertEquals(existedBefore, fileBefore.exists())
        assertEquals(fileBefore.absolutePath, vm.sessionFile.absolutePath)
        assertTrue(vm.messages.value.isEmpty())
    }

    // (P5.4-f) Double-tapping End must not save or end the session twice.
    @Test
    fun endSession_calledTwiceBeforeCompleting_savesOnlyOnce() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Reply"
        val vm = newViewModel()
        vm.send("Only one recording please")
        advanceUntilIdle()

        vm.endSession()
        vm.endSession()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.save(any()) }
        val endedFiles = conversationsDir().listFiles()?.filter { it.name.contains("ended") } ?: emptyList()
        assertEquals(1, endedFiles.size)
    }

    // (P5.4-g) A failure during analysis leaves the session live and resumable (not marked ended),
    //     so the user can retry End rather than losing the meeting.
    @Test
    fun endSession_analysisFailure_leavesSessionResumable() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Reply"
        val vm = newViewModel()
        vm.send("Keep this session alive")
        advanceUntilIdle()
        val originalFile = vm.sessionFile

        coEvery { llm.generate(any(), any<String>()) } throws RuntimeException("model exploded")

        vm.endSession()
        advanceUntilIdle()

        assertNotNull(vm.error.value)
        assertTrue("session file should still be live", originalFile.exists())
        assertEquals(originalFile.absolutePath, vm.sessionFile.absolutePath)
        assertTrue(vm.messages.value.isNotEmpty())
        val endedFiles = conversationsDir().listFiles()?.filter { it.name.contains("ended") } ?: emptyList()
        assertTrue(endedFiles.isEmpty())
    }

    // (P6.1-a) start -> stop: recorder started/stopped, transcription invoked with the recorded
    //     file, voiceTranscript exposes the text, and the temp audio file is deleted afterward.
    @Test
    fun voiceInput_startThenStop_transcribesAndCleansUpTempFile() = runTest {
        markWhisperReady()
        val startedFile = slot<File>()
        every { audioRecorder.start(capture(startedFile), any()) } answers {
            startedFile.captured.parentFile?.mkdirs()
            startedFile.captured.writeBytes(byteArrayOf(1, 2, 3))
        }
        coEvery { transcriptionService.transcribe(any()) } returns "hello there"
        val vm = newViewModel()

        vm.startVoiceInput()
        assertTrue(vm.isRecordingVoice.value)
        verify { audioRecorder.start(any(), any()) }

        vm.stopVoiceInput()
        verify { audioRecorder.stop() }
        assertFalse(vm.isRecordingVoice.value)

        advanceUntilIdle()

        assertFalse(vm.isTranscribing.value)
        assertEquals("hello there", vm.voiceTranscript.value)
        coVerify { transcriptionService.transcribe(startedFile.captured) }
        assertFalse("temp audio file should be deleted after transcription", startedFile.captured.exists())
        assertNull(vm.error.value)
    }

    // (P6.1-b) An empty/whitespace transcription result sets the error flow with a short message
    //     and does not populate voiceTranscript.
    @Test
    fun voiceInput_emptyTranscription_setsErrorNoTranscript() = runTest {
        markWhisperReady()
        every { audioRecorder.start(any(), any()) } returns Unit
        coEvery { transcriptionService.transcribe(any()) } returns "   "
        val vm = newViewModel()

        vm.startVoiceInput()
        vm.stopVoiceInput()
        advanceUntilIdle()

        assertEquals("Didn't catch that", vm.error.value)
        assertNull(vm.voiceTranscript.value)
        assertFalse(vm.isTranscribing.value)
    }

    // (P6.1-c) A transcription failure sets the error flow, clears isTranscribing/isRecordingVoice,
    //     and still cleans up the temp file.
    @Test
    fun voiceInput_transcriptionThrows_setsErrorClearsStateAndCleansUpTempFile() = runTest {
        markWhisperReady()
        val startedFile = slot<File>()
        every { audioRecorder.start(capture(startedFile), any()) } answers {
            startedFile.captured.parentFile?.mkdirs()
            startedFile.captured.writeBytes(byteArrayOf(1, 2, 3))
        }
        coEvery { transcriptionService.transcribe(any()) } throws RuntimeException("boom")
        val vm = newViewModel()

        vm.startVoiceInput()
        vm.stopVoiceInput()
        advanceUntilIdle()

        assertNotNull(vm.error.value)
        assertFalse(vm.isTranscribing.value)
        assertFalse(vm.isRecordingVoice.value)
        assertNull(vm.voiceTranscript.value)
        assertFalse("temp audio file should still be cleaned up", startedFile.captured.exists())
    }

    // (P6.1-d) If the Whisper model isn't downloaded, starting voice input sets an error
    //     directing the user to Settings and never starts the recorder.
    @Test
    fun voiceInput_modelUnavailable_setsErrorAndNeverStartsRecorder() = runTest {
        val vm = newViewModel()

        vm.startVoiceInput()

        assertFalse(vm.isRecordingVoice.value)
        assertNotNull(vm.error.value)
        verify(exactly = 0) { audioRecorder.start(any(), any()) }
    }

    // (P6.1-e) Abandoning an in-progress recording (screen disposed) releases the recorder and
    //     drops the temp file without transcribing — an unstopped recorder would hold the mic.
    @Test
    fun cancelVoiceInput_whileRecording_releasesRecorderAndDropsTempFile() = runTest {
        markWhisperReady()
        val startedFile = slot<File>()
        every { audioRecorder.start(capture(startedFile), any()) } answers {
            startedFile.captured.parentFile?.mkdirs()
            startedFile.captured.writeBytes(byteArrayOf(1, 2, 3))
        }
        val vm = newViewModel()

        vm.startVoiceInput()
        vm.cancelVoiceInput()
        advanceUntilIdle()

        verify { audioRecorder.stop() }
        assertFalse(vm.isRecordingVoice.value)
        assertFalse(vm.isTranscribing.value)
        assertNull(vm.voiceTranscript.value)
        assertFalse("abandoned audio file should be deleted", startedFile.captured.exists())
        coVerify(exactly = 0) { transcriptionService.transcribe(any()) }
    }

    // (P6.1-f) Cancelling with nothing in flight is a no-op — it must not touch the recorder.
    @Test
    fun cancelVoiceInput_whenIdle_isNoOp() = runTest {
        val vm = newViewModel()

        vm.cancelVoiceInput()

        verify(exactly = 0) { audioRecorder.stop() }
        assertFalse(vm.isRecordingVoice.value)
    }

    // (P6.2-a) A MODEL reply speaks through the TTS wrapper when the toggle is enabled and TTS
    //     is available.
    @Test
    fun send_ttsEnabled_speaksModelReply() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Here's a reply"
        val vm = newViewModel()
        vm.setTtsEnabled(true)

        vm.send("Hello")
        advanceUntilIdle()

        verify(exactly = 1) { tts.speak("Here's a reply") }
    }

    // (P6.2-b) No speak() call when the toggle is disabled (the default).
    @Test
    fun send_ttsDisabled_doesNotSpeak() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Here's a reply"
        val vm = newViewModel()

        vm.send("Hello")
        advanceUntilIdle()

        verify(exactly = 0) { tts.speak(any()) }
    }

    // (P6.2-c) Even with the toggle enabled, an unavailable TTS engine never speaks — this must
    //     behave exactly like disabled, with no error surfaced.
    @Test
    fun send_ttsEnabledButUnavailable_doesNotSpeak() = runTest {
        every { tts.isAvailable } returns false
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Here's a reply"
        val vm = newViewModel()
        vm.setTtsEnabled(true)

        vm.send("Hello")
        advanceUntilIdle()

        verify(exactly = 0) { tts.speak(any()) }
        assertNull(vm.error.value)
    }

    // (P6.2-d) Starting a new user action must never talk over the user: send(), startVoiceInput(),
    //     endSession(), and startNewSession() each stop any active speech first.
    @Test
    fun send_stopsActiveSpeechFirst() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel()
        vm.setTtsEnabled(true)

        vm.send("Hello")

        verify(exactly = 1) { tts.stop() }
        advanceUntilIdle()
    }

    @Test
    fun startVoiceInput_stopsActiveSpeechFirst() = runTest {
        markWhisperReady()
        val vm = newViewModel()
        vm.setTtsEnabled(true)

        vm.startVoiceInput()

        verify(exactly = 1) { tts.stop() }
    }

    @Test
    fun endSession_stopsActiveSpeechFirst() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel()
        vm.setTtsEnabled(true)
        vm.send("Hello")
        advanceUntilIdle()

        vm.endSession()

        verify(exactly = 2) { tts.stop() } // once from send(), once from endSession()
        advanceUntilIdle()
    }

    @Test
    fun startNewSession_stopsActiveSpeechFirst() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel()
        vm.setTtsEnabled(true)
        vm.send("Hello")
        advanceUntilIdle()

        vm.startNewSession()

        verify(exactly = 2) { tts.stop() } // once from send(), once from startNewSession()
        advanceUntilIdle()
    }

    // (P6.2-e) onCleared() shuts down the TTS engine it built.
    @Test
    fun onCleared_shutsDownTts() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel()
        vm.setTtsEnabled(true)
        vm.send("Hello") // builds the engine
        advanceUntilIdle()

        val method = ConversationViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(vm)

        verify(exactly = 1) { tts.shutdown() }
    }

    // (P6.2-g) A user who never turns spoken replies on must never pay to build the speech
    //     engine: on a real device that binds the system TextToSpeech service. Regression guard —
    //     the per-turn stop() calls previously forced construction for everyone.
    @Test
    fun ttsNeverEnabled_neverBuildsSpeechEngine() = runTest {
        markWhisperReady()
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel()

        vm.send("Hello")
        advanceUntilIdle()
        vm.startVoiceInput()
        vm.startNewSession()
        advanceUntilIdle()
        vm.stopSpeaking()

        assertEquals(0, ttsConstructions)
    }

    // (P6.2-h) Muting mid-reply silences what is already being spoken.
    @Test
    fun setTtsEnabledFalse_stopsSpeechInProgress() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel()
        vm.setTtsEnabled(true)
        vm.send("Hello")
        advanceUntilIdle()

        vm.setTtsEnabled(false)

        verify(exactly = 2) { tts.stop() } // once from send(), once from muting
    }

    // (P6.2-f) The toggle persists to SharedPreferences and is restored by a fresh ViewModel.
    @Test
    fun setTtsEnabled_persistsAndRestoredByNewViewModel() = runTest {
        val vm = newViewModel()
        assertFalse(vm.ttsEnabled.value)

        vm.setTtsEnabled(true)
        assertTrue(vm.ttsEnabled.value)
        assertTrue(prefs().getBoolean("conversation_tts_enabled", false))

        val reloaded = newViewModel()
        assertTrue(reloaded.ttsEnabled.value)
    }

    // (P8.1) Enabled-state predicate backing the "New conversation" menu item.
    // (P8.2-a) setTtsRate persists, applies to the engine, and previews when TTS is enabled.
    @Test
    fun setTtsRate_ttsEnabled_persistsAppliesToEngineAndPreviews() = runTest {
        val vm = newViewModel()
        vm.setTtsEnabled(true)

        vm.setTtsRate(1.5f)

        assertEquals(1.5f, vm.ttsRate.value)
        assertEquals(1.5f, prefs().getFloat(CONVERSATION_TTS_RATE_KEY, -1f))
        verify(exactly = 1) { tts.setSpeechRate(1.5f) }
        verify(exactly = 1) { tts.preview(any()) }
    }

    // (P8.2-a) setTtsVoice persists, applies to the engine, and previews when TTS is enabled.
    @Test
    fun setTtsVoice_ttsEnabled_persistsAppliesToEngineAndPreviews() = runTest {
        every { tts.setVoice("Voice-1") } returns true
        val vm = newViewModel()
        vm.setTtsEnabled(true)

        vm.setTtsVoice("Voice-1")

        assertEquals("Voice-1", vm.ttsVoiceId.value)
        assertEquals("Voice-1", prefs().getString(CONVERSATION_TTS_VOICE_KEY, null))
        verify(exactly = 1) { tts.setVoice("Voice-1") }
        verify(exactly = 1) { tts.preview(any()) }
    }

    // (P8.2-b) With TTS disabled and the engine never built, setTtsRate/setTtsVoice must persist
    //     only — never constructing the speech engine (mirrors ttsNeverEnabled_neverBuildsSpeechEngine).
    @Test
    fun setTtsRateAndVoice_ttsDisabledEngineNeverBuilt_persistOnlyDoNotConstructEngine() = runTest {
        val vm = newViewModel()

        vm.setTtsRate(1.25f)
        vm.setTtsVoice("some-voice")

        assertEquals(1.25f, vm.ttsRate.value)
        assertEquals("some-voice", vm.ttsVoiceId.value)
        assertEquals(1.25f, prefs().getFloat(CONVERSATION_TTS_RATE_KEY, -1f))
        assertEquals("some-voice", prefs().getString(CONVERSATION_TTS_VOICE_KEY, null))
        assertEquals(0, ttsConstructions)
    }

    // (P8.2-c) Persisted rate+voice are applied to the engine when it is first constructed
    //     (the lazy warm path).
    @Test
    fun ttsEngineWarm_appliesPersistedRateAndVoiceOnFirstBuild() = runTest {
        prefs().edit()
            .putFloat(CONVERSATION_TTS_RATE_KEY, 1.75f)
            .putString(CONVERSATION_TTS_VOICE_KEY, "Voice-X")
            .commit()
        every { tts.setVoice("Voice-X") } returns true
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel()
        vm.setTtsEnabled(true)

        vm.send("Hello")
        advanceUntilIdle()

        verify(exactly = 1) { tts.setSpeechRate(1.75f) }
        verify(exactly = 1) { tts.setVoice("Voice-X") }
        assertEquals(1, ttsConstructions)
    }

    // (P8.2-d) An unknown persisted voice id: setVoice() returns false, and the ViewModel must not
    //     crash and must NOT clear the pref — it silently falls back to the system default.
    @Test
    fun ttsEngineWarm_unknownPersistedVoiceId_setVoiceFalse_noCrashPrefRetained() = runTest {
        prefs().edit().putString(CONVERSATION_TTS_VOICE_KEY, "Ghost-Voice").commit()
        every { tts.setVoice("Ghost-Voice") } returns false
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "reply"
        val vm = newViewModel()
        vm.setTtsEnabled(true)

        vm.send("Hello")
        advanceUntilIdle()

        verify(exactly = 1) { tts.setVoice("Ghost-Voice") }
        assertEquals("Ghost-Voice", prefs().getString(CONVERSATION_TTS_VOICE_KEY, null))
        assertNull(vm.error.value)
    }

    // (P8.2) availableVoices() passes through to the engine.
    @Test
    fun availableVoices_returnsEngineVoices() = runTest {
        every { tts.availableVoices() } returns listOf(VoiceInfo("a", "Voice 1"), VoiceInfo("b", "Voice 2"))
        val vm = newViewModel()
        vm.setTtsEnabled(true)

        val voices = vm.availableVoices()

        assertEquals(2, voices.size)
        assertEquals("Voice 1", voices[0].label)
    }

    // (P8.2-b) Opening the voice picker with spoken replies off and the engine never built must
    //     report no voices rather than binding a TextToSpeech engine the user isn't using.
    @Test
    fun availableVoices_ttsDisabledEngineNeverBuilt_returnsEmptyWithoutConstructingEngine() = runTest {
        every { tts.availableVoices() } returns listOf(VoiceInfo("a", "Voice 1"))
        val vm = newViewModel()

        assertTrue(vm.availableVoices().isEmpty())
        assertEquals(0, ttsConstructions)
    }

    // (P8.2-g) With the engine already built but spoken replies switched off, a settings change
    //     still reaches the engine but must not speak — the user asked for silence.
    @Test
    fun setTtsRate_engineBuiltButTtsDisabled_appliesToEngineWithoutPreviewing() = runTest {
        val vm = newViewModel()
        vm.setTtsEnabled(true)
        vm.setTtsRate(1.5f) // builds the engine and previews once
        vm.setTtsEnabled(false)

        vm.setTtsRate(0.75f)

        assertEquals(0.75f, vm.ttsRate.value)
        verify(exactly = 1) { tts.setSpeechRate(0.75f) }
        verify(exactly = 1) { tts.preview(any()) } // still just the enabled-state preview
    }

    // (P8.2-b) Picking "System default" persists the empty id and forwards it to the engine, which
    //     is what restores the engine's original voice after a custom one was applied live.
    @Test
    fun setTtsVoice_systemDefault_persistsEmptyIdAndForwardsItToEngine() = runTest {
        every { tts.setVoice(any()) } returns true
        val vm = newViewModel()
        vm.setTtsEnabled(true)
        vm.setTtsVoice("Voice-1")

        vm.setTtsVoice("")

        assertEquals("", vm.ttsVoiceId.value)
        assertEquals("", prefs().getString(CONVERSATION_TTS_VOICE_KEY, null))
        verify(exactly = 1) { tts.setVoice("") }
    }

    // (P8.2) Defaults: rate 1.0f, voice "" (system default), before any preference is set.
    @Test
    fun ttsRateAndVoice_defaults() = runTest {
        val vm = newViewModel()

        assertEquals(1.0f, vm.ttsRate.value)
        assertEquals("", vm.ttsVoiceId.value)
    }

    // (P8.3-a) Instant send ON: a non-blank transcription is sent through the same pipeline as
    //     send() directly — user+model messages appear, the file gets both turns — and
    //     voiceTranscript stays null (the input field is never touched on this path).
    @Test
    fun voiceInput_instantSendOn_nonBlankTranscription_triggersSendPipeline() = runTest {
        markWhisperReady()
        every { audioRecorder.start(any(), any()) } returns Unit
        coEvery { transcriptionService.transcribe(any()) } returns "let's ship it"
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Great idea!"
        val vm = newViewModel()
        vm.setInstantSend(true)

        vm.startVoiceInput()
        vm.stopVoiceInput()
        advanceUntilIdle()

        assertNull(vm.voiceTranscript.value)
        val messages = vm.messages.value
        assertEquals(2, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("let's ship it", messages[0].text)
        assertEquals(Role.MODEL, messages[1].role)
        assertEquals("Great idea!", messages[1].text)

        val content = vm.sessionFile.readText()
        assertTrue(content.contains("let's ship it"))
        assertTrue(content.contains("Great idea!"))
    }

    // (P8.3-c) Instant send ON but the transcription is blank: unchanged "Didn't catch that"
    //     error, nothing sent, regardless of the toggle.
    @Test
    fun voiceInput_instantSendOn_blankTranscription_setsErrorNoSend() = runTest {
        markWhisperReady()
        every { audioRecorder.start(any(), any()) } returns Unit
        coEvery { transcriptionService.transcribe(any()) } returns "   "
        val vm = newViewModel()
        vm.setInstantSend(true)

        vm.startVoiceInput()
        vm.stopVoiceInput()
        advanceUntilIdle()

        assertEquals("Didn't catch that", vm.error.value)
        assertNull(vm.voiceTranscript.value)
        assertTrue(vm.messages.value.isEmpty())
        coVerify(exactly = 0) { llm.generate(any(), any<List<ChatTurn>>()) }
    }

    // (P8.3-d) Instant send ON but a generation is already in flight (e.g. the user sent typed
    //     text while the voice recording was still going): the send pipeline's guard must reject
    //     the instant path so it never double-sends. The transcript must not be lost — it falls
    //     back to voiceTranscript instead, same as instant send OFF.
    @Test
    fun voiceInput_instantSendOn_generationInProgress_fallsBackToVoiceTranscriptNoCrash() = runTest {
        markWhisperReady()
        val gate = CompletableDeferred<String>()
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } coAnswers { gate.await() }
        every { audioRecorder.start(any(), any()) } returns Unit
        coEvery { transcriptionService.transcribe(any()) } returns "fallback text"
        val vm = newViewModel()
        vm.setInstantSend(true)

        vm.startVoiceInput()
        vm.send("typed while recording")
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.isGenerating.value)

        vm.stopVoiceInput()
        advanceUntilIdle()

        assertEquals("fallback text", vm.voiceTranscript.value)
        assertNull(vm.error.value)
        assertEquals(1, vm.messages.value.count { it.role == Role.USER })

        gate.complete("ok")
        advanceUntilIdle()
        assertFalse(vm.isGenerating.value)
    }

    // (P8.3-e) The toggle persists to SharedPreferences and is restored by a fresh ViewModel.
    @Test
    fun setInstantSend_persistsAndRestoredByNewViewModel() = runTest {
        val vm = newViewModel()
        assertFalse(vm.instantSend.value)

        vm.setInstantSend(true)
        assertTrue(vm.instantSend.value)
        assertTrue(prefs().getBoolean("conversation_instant_send", false))

        val reloaded = newViewModel()
        assertTrue(reloaded.instantSend.value)
    }

    @Test
    fun canStartNewSession_falseWhenNoMessages() {
        assertFalse(canStartNewSession(emptyList(), isGenerating = false))
    }

    @Test
    fun canStartNewSession_falseWhileGenerating() {
        val messages = listOf(ChatMessage(Role.USER, "hi", 0L))
        assertFalse(canStartNewSession(messages, isGenerating = true))
    }

    @Test
    fun canStartNewSession_trueWithMessagesAndNotGenerating() {
        val messages = listOf(ChatMessage(Role.USER, "hi", 0L))
        assertTrue(canStartNewSession(messages, isGenerating = false))
    }
}
