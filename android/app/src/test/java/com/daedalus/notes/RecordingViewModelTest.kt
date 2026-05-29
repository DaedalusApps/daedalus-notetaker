package com.daedalus.notes

import android.app.Application
import com.daedalus.notes.ble.BleManager
import com.daedalus.notes.ble.BleState
import com.daedalus.notes.ble.ConnectionState
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.db.AppDatabase
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
    
    // We will inject these via reflection or refactoring if needed, 
    // but for TDD we'll first define the desired behavior.
    private lateinit var viewModel: RecordingViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        every { repo.allRecordings } returns flowOf(emptyList())
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED)
        )
        
        // Mock all dependencies that would touch Android internals
        val db = mockk<AppDatabase>(relaxed = true)
        val llm = mockk<LocalLlmService>(relaxed = true)
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        
        viewModel = RecordingViewModel(
            application = application,
            db = db,
            repo = repo,
            llm = llm,
            transcriber = transcriber
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
}
