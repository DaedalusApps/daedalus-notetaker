package com.daedalus.notes

import android.app.Application
import android.util.Log
import com.daedalus.notes.ble.BleManager
import com.daedalus.notes.ble.BleState
import com.daedalus.notes.ble.ConnectionState
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.ai.EmbeddingService
import com.daedalus.notes.ai.LocalLlmService
import com.daedalus.notes.ai.TranscriptionService
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.viewmodel.RecordingViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.arch.core.executor.testing.InstantTaskExecutorRule

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val application = mockk<Application>(relaxed = true)
    private val repo = mockk<RecordingRepository>(relaxed = true)
    private val bleManager = mockk<BleManager>(relaxed = true)
    private val embedder = mockk<EmbeddingService>(relaxed = true)
    private val llm = mockk<LocalLlmService>(relaxed = true)
    
    private lateinit var viewModel: RecordingViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        
        every { repo.allRecordings } returns flowOf(emptyList())
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED)
        )
        
        // Mock all dependencies that would touch Android internals
        val db = mockk<AppDatabase>(relaxed = true)
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        
        viewModel = RecordingViewModel(
            application = application,
            db = db,
            repo = repo,
            llm = llm,
            transcriber = transcriber,
            embedder = embedder
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun askLibraryQuestion_updatesLibraryAnswer() = runTest {
        val question = "What is the meaning of life?"
        val answer = "42"
        val recordings = listOf(
            Recording("note1.mp3", title = "Note 1", summary = "Summary 1", shortSummary = "Short 1")
        )
        
        every { embedder.isReady } returns true
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)
        every { repo.allRecordings } returns flowOf(recordings)
        coEvery { repo.semanticSearch(any(), any(), any()) } returns recordings
        coEvery { llm.generate(any(), any()) } returns answer

        viewModel.askLibraryQuestion(question)
        advanceUntilIdle()

        assertEquals(answer, viewModel.libraryAnswer.value)
        assertEquals(recordings, viewModel.librarySources.value)
        assertEquals(question, viewModel.libraryQuestion.value)
    }

    @Test
    fun deleteMultipleRecordings_updatesProgressAndCallsDelete() = runTest {
        val filenames = listOf("file1.mp3", "file2.mp3")
        coEvery { repo.get("file1.mp3") } returns Recording("file1.mp3", durationMillis = 1000L)
        coEvery { repo.get("file2.mp3") } returns Recording("file2.mp3", durationMillis = 2000L)
        coEvery { bleManager.deleteFile(any()) } returns true

        viewModel.deleteMultipleRecordings(filenames, bleManager)
        
        // Advance time to allow coroutine to run
        advanceUntilIdle()

        // Verify hardware delete called twice
        coVerify(exactly = 1) { bleManager.deleteFile("file1.mp3") }
        coVerify(exactly = 1) { bleManager.deleteFile("file2.mp3") }
        
        // Verify repo delete called twice
        coVerify(exactly = 2) { repo.delete(any()) }
        
        // Final progress should be null
        assertEquals(null, viewModel.syncProgress.value)
    }

    @Test
    fun deleteRecording_deviceFile_deletesFromHardware() = runTest {
        coEvery { repo.get("dev.mp3") } returns Recording("dev.mp3", isLocal = false)
        coEvery { bleManager.deleteFile("dev.mp3") } returns true

        viewModel.deleteRecording("dev.mp3", bleManager)
        advanceUntilIdle()

        // Device recording must be wiped from the FW920 over BLE, then removed locally.
        coVerify(exactly = 1) { bleManager.deleteFile("dev.mp3") }
        coVerify(exactly = 1) { repo.delete(any()) }
    }

    @Test
    fun deleteRecording_localFile_skipsHardware() = runTest {
        coEvery { repo.get("local.m4a") } returns Recording("local.m4a", isLocal = true)

        viewModel.deleteRecording("local.m4a", bleManager)
        advanceUntilIdle()

        // Local-only recordings aren't on the device — no BLE delete should be attempted.
        coVerify(exactly = 0) { bleManager.deleteFile(any()) }
        coVerify(exactly = 1) { repo.delete(any()) }
    }

    @Test
    fun cancelSync_clearsSyncProgress() = runTest {
        // cancelSync with no active job should not throw and should clear progress
        viewModel.cancelSync()
        advanceUntilIdle()
        assertNull(viewModel.syncProgress.value)
    }

    @Test
    fun updateTitleAndSummary_delegatesToRepo() = runTest {
        viewModel.updateTitleAndSummary("rec.mp3", "New Title", "New summary")
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.updateTitleAndSummary("rec.mp3", "New Title", "New summary") }
    }

    @Test
    fun deleteRecording_whenDisconnected_queuesDelete() = runTest {
        val stateFlow = MutableStateFlow(BleState(connectionState = ConnectionState.DISCONNECTED))
        every { bleManager.bleState } returns stateFlow
        
        coEvery { repo.get("dev.mp3") } returns Recording("dev.mp3", isLocal = false)

        viewModel.deleteRecording("dev.mp3", bleManager)
        advanceUntilIdle()

        // Device recording must not be deleted over BLE, but must be marked as pending delete.
        coVerify(exactly = 0) { bleManager.deleteFile(any()) }
        coVerify(exactly = 1) { repo.markPendingDelete("dev.mp3") }
    }

    @Test
    fun deleteMultipleRecordings_whenDisconnected_queuesDeletes() = runTest {
        val stateFlow = MutableStateFlow(BleState(connectionState = ConnectionState.DISCONNECTED))
        every { bleManager.bleState } returns stateFlow
        
        coEvery { repo.get("file1.mp3") } returns Recording("file1.mp3", isLocal = false)
        coEvery { repo.get("file2.mp3") } returns Recording("file2.mp3", isLocal = false)

        viewModel.deleteMultipleRecordings(listOf("file1.mp3", "file2.mp3"), bleManager)
        advanceUntilIdle()

        // Verify no BLE delete attempts
        coVerify(exactly = 0) { bleManager.deleteFile(any()) }
        // Verify repo markPendingDelete called
        coVerify(exactly = 1) { repo.markPendingDelete("file1.mp3") }
        coVerify(exactly = 1) { repo.markPendingDelete("file2.mp3") }
    }

    @Test
    fun syncAllBleFiles_processesPendingDeletions() = runTest {
        val pending = listOf(
            Recording("pending1.mp3", isLocal = false, pendingDelete = true),
            Recording("pending2.mp3", isLocal = false, pendingDelete = true)
        )
        coEvery { repo.getPendingDeletes() } returns pending
        coEvery { bleManager.deleteFile(any()) } returns true
        
        // Mock listFiles to not throw
        coEvery { bleManager.listFiles() } returns Unit
        // Mock files list returned on bleState
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(
                connectionState = ConnectionState.CONNECTED,
                files = emptyList() // No files to download
            )
        )

        viewModel.syncAllBleFiles(bleManager)
        advanceUntilIdle()

        // Verify pending deletions are processed via BLE
        coVerify(exactly = 1) { bleManager.deleteFile("pending1.mp3") }
        coVerify(exactly = 1) { bleManager.deleteFile("pending2.mp3") }
        
        // Verify pending deletions are removed from database
        coVerify(exactly = 1) { repo.delete(pending[0]) }
        coVerify(exactly = 1) { repo.delete(pending[1]) }
    }
}
