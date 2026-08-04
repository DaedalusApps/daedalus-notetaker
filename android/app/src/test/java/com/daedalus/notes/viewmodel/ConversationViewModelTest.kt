package com.daedalus.notes.viewmodel

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.daedalus.notes.ai.ChatTurn
import com.daedalus.notes.ai.LocalLlmService
import com.daedalus.notes.ai.Role
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConversationViewModelTest {

    private lateinit var application: Application
    private val llm = mockk<LocalLlmService>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    // Fixed instant so filenames/day comparisons are deterministic across the test run.
    private val nowMillis = 1_700_000_000_000L

    private fun conversationsDir(): File = File(application.filesDir, "conversations")

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

        conversationsDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        conversationsDir().deleteRecursively()
    }

    private fun newViewModel(): ConversationViewModel = ConversationViewModel(
        application = application,
        llm = llm,
        ioDispatcher = testDispatcher,
        clock = { nowMillis }
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
}
