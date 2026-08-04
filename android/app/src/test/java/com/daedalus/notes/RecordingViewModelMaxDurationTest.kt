package com.daedalus.notes

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.daedalus.notes.ai.EmbeddingService
import com.daedalus.notes.ai.LocalLlmService
import com.daedalus.notes.ai.TranscriptionService
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.recording.AudioRecorder
import com.daedalus.notes.viewmodel.MAX_RECORDING_MINUTES_DEFAULT
import com.daedalus.notes.viewmodel.MAX_RECORDING_MINUTES_KEY
import com.daedalus.notes.viewmodel.MAX_RECORDING_MINUTES_UNLIMITED
import com.daedalus.notes.viewmodel.RecordingViewModel
import com.daedalus.notes.data.model.Recording
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import java.io.File

/**
 * Covers the P4.2 max recording duration auto-stop behavior: the elapsed-seconds timer
 * job in RecordingViewModel must stop and save a local recording once it hits the
 * configured cap, through the same stopLocalRecording() path a manual stop uses, and
 * surface a notice for the UI to show as a snackbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelMaxDurationTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val application = mockk<Application>(relaxed = true)
    private val repo = mockk<RecordingRepository>(relaxed = true)
    private val embedder = mockk<EmbeddingService>(relaxed = true)
    private val llm = mockk<LocalLlmService>(relaxed = true)
    private val recordingDao = mockk<com.daedalus.notes.data.db.RecordingDao>(relaxed = true)
    private val db = mockk<AppDatabase>(relaxed = true)
    private val fakePrefs = mockk<SharedPreferences>(relaxed = true)
    private val fakeAudioRecorder = mockk<AudioRecorder>(relaxed = true)

    private lateinit var viewModel: RecordingViewModel
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any() as String) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        every { repo.allRecordings } returns flowOf(emptyList())
        every { db.recordingDao() } returns recordingDao
        coEvery { recordingDao.get(any()) } returns null

        every { application.getSharedPreferences(any(), any()) } returns fakePrefs
        every { fakePrefs.getInt(MAX_RECORDING_MINUTES_KEY, MAX_RECORDING_MINUTES_DEFAULT) } returns MAX_RECORDING_MINUTES_DEFAULT
        every { fakePrefs.getBoolean(any(), any()) } returns false

        tempDir = java.nio.file.Files.createTempDirectory("daedalus_max_duration_test").toFile()
        every { application.getExternalFilesDir(null) } returns tempDir

        // Simulate the recorder actually producing bytes on start, so the same
        // file.exists()/length()>0 check that gates repo.save() in stopLocalRecording()
        // passes for both manual and auto-stop paths.
        every { fakeAudioRecorder.start(any(), any()) } answers {
            (firstArg() as File).writeBytes(byteArrayOf(1, 2, 3))
        }

        val transcriber = mockk<TranscriptionService>(relaxed = true)

        viewModel = RecordingViewModel(
            application = application,
            db = db,
            repo = repo,
            llm = llm,
            transcriber = transcriber,
            embedder = embedder,
            ioDispatcher = testDispatcher,
            audioRecorderProvider = { fakeAudioRecorder },
            timerDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        tempDir.deleteRecursively()
    }

    @Test
    fun timer_capReached_autoStopsAndSavesThroughManualStopPath() = runTest {
        every { fakePrefs.getInt(MAX_RECORDING_MINUTES_KEY, MAX_RECORDING_MINUTES_DEFAULT) } returns 1 // 1 minute cap

        viewModel.startLocalRecording()
        advanceUntilIdle()

        assertFalse("recording should have auto-stopped", viewModel.isRecording.value)
        assertNotNull("an auto-stop notice must be surfaced for the UI", viewModel.autoStopNotice.value)
        coVerify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun timer_firesOnTheTickThatReachesTheCap_notBefore() = runTest {
        every { fakePrefs.getInt(MAX_RECORDING_MINUTES_KEY, MAX_RECORDING_MINUTES_DEFAULT) } returns 1 // 1 minute cap

        viewModel.startLocalRecording()
        advanceTimeBy(59_000L + 500)

        assertTrue("must still be recording one second before the cap", viewModel.isRecording.value)
        assertEquals(59L, viewModel.recordingDurationSeconds.value)
        assertNull("no notice before the cap is reached", viewModel.autoStopNotice.value)

        advanceTimeBy(1_000L)

        assertFalse("must auto-stop on the tick that reaches the cap", viewModel.isRecording.value)
        assertEquals(60L, viewModel.recordingDurationSeconds.value)
        assertNotNull(viewModel.autoStopNotice.value)
        advanceUntilIdle()
    }

    @Test
    fun timer_pausedTimeDoesNotCountTowardTheCap() = runTest {
        every { fakePrefs.getInt(MAX_RECORDING_MINUTES_KEY, MAX_RECORDING_MINUTES_DEFAULT) } returns 1 // 1 minute cap

        viewModel.startLocalRecording()
        advanceTimeBy(30_000L + 500)
        viewModel.pauseLocalRecording()
        assertEquals(30L, viewModel.recordingDurationSeconds.value)

        advanceTimeBy(5 * 60_000L)
        assertTrue("paused time must not trip the cap", viewModel.isRecording.value)
        assertEquals(30L, viewModel.recordingDurationSeconds.value)
        assertNull(viewModel.autoStopNotice.value)

        viewModel.resumeLocalRecording()
        advanceTimeBy(30_000L + 500)

        assertFalse("cap must still fire after resuming", viewModel.isRecording.value)
        assertEquals(60L, viewModel.recordingDurationSeconds.value)
        assertNotNull(viewModel.autoStopNotice.value)
        advanceUntilIdle()
    }

    @Test
    fun timer_unlimitedSentinel_neverAutoStops() = runTest {
        every { fakePrefs.getInt(MAX_RECORDING_MINUTES_KEY, MAX_RECORDING_MINUTES_DEFAULT) } returns MAX_RECORDING_MINUTES_UNLIMITED

        viewModel.startLocalRecording()
        advanceTimeBy(3 * 60_000L + 500)

        assertTrue("recording must still be active with no cap", viewModel.isRecording.value)
        assertNull("no auto-stop notice should be set", viewModel.autoStopNotice.value)

        viewModel.stopLocalRecording()
        advanceUntilIdle()
    }

    @Test
    fun clearAutoStopNotice_resetsState() = runTest {
        every { fakePrefs.getInt(MAX_RECORDING_MINUTES_KEY, MAX_RECORDING_MINUTES_DEFAULT) } returns 1

        viewModel.startLocalRecording()
        advanceUntilIdle()
        assertNotNull(viewModel.autoStopNotice.value)

        viewModel.clearAutoStopNotice()
        assertNull(viewModel.autoStopNotice.value)
    }

    // --- #30: saved durationMillis must exclude paused time ---

    @Test
    fun stop_afterPauseAndResume_savesDurationExcludingPausedTime() = runTest {
        val savedSlot = slot<Recording>()
        coEvery { repo.save(capture(savedSlot)) } returns Unit

        viewModel.startLocalRecording()
        advanceTimeBy(60_000L) // 60s recorded
        viewModel.pauseLocalRecording()

        advanceTimeBy(5 * 60_000L) // 5 minutes paused — must not count

        viewModel.resumeLocalRecording()
        advanceTimeBy(30_000L) // 30s more recorded

        viewModel.stopLocalRecording()
        advanceUntilIdle()

        // Expect ~90s (60 + 30), not ~390s (60 + 300 + 30) from wall-clock.
        assertEquals(90_000L, savedSlot.captured.durationMillis)
    }

    @Test
    fun stop_withoutPause_savesDurationMatchingElapsed() = runTest {
        val savedSlot = slot<Recording>()
        coEvery { repo.save(capture(savedSlot)) } returns Unit

        viewModel.startLocalRecording()
        advanceTimeBy(45_000L)

        viewModel.stopLocalRecording()
        advanceUntilIdle()

        assertEquals(45_000L, savedSlot.captured.durationMillis)
    }
}
