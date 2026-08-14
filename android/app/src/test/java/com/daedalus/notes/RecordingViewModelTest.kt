package com.daedalus.notes

import android.app.Application
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Wraps a real dispatcher and counts how many times coroutines are actually dispatched onto
 * it. Used to pin dispatcher ROUTING deterministically (issue #136): if production code
 * ignores the injected `ioDispatcher` and uses a raw `Dispatchers.IO` instead, this count
 * stays at 0 no matter how long we wait — it does not depend on real-thread timing, so it
 * can't be a flake in either direction.
 *
 * Delegates [Delay] to the wrapped dispatcher (always a [StandardTestDispatcher] in this file)
 * so virtual time is preserved. Without this, a `delay()` call while this dispatcher is the
 * interceptor would fall back to real wall-clock `DefaultDelay`, `advanceUntilIdle()` would
 * return early, and the coroutine could outlive the test.
 */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
private class DispatchCountingDispatcher(
    private val delegate: CoroutineDispatcher
) : CoroutineDispatcher(), Delay by (delegate as Delay) {
    var dispatchCount = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount++
        delegate.dispatch(context, block)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val application = mockk<Application>(relaxed = true)
    private val repo = mockk<RecordingRepository>(relaxed = true)
    private val bleManager = mockk<BleManager>(relaxed = true)
    private val embedder = mockk<EmbeddingService>(relaxed = true)
    private val llm = mockk<LocalLlmService>(relaxed = true)
    // Backup import/export now runs through BackupManager, which builds its own
    // RecordingRepository from db.recordingDao(); the import tests below verify
    // against this DAO rather than the injected repo.
    private val recordingDao = mockk<com.daedalus.notes.data.db.RecordingDao>(relaxed = true)
    private val db = mockk<AppDatabase>(relaxed = true)

    private lateinit var viewModel: RecordingViewModel
    private val testDispatcher = StandardTestDispatcher()

    // Production code creates a Recordings/ subdirectory inside temp dirs handed out as
    // getExternalFilesDir(null), so File.deleteOnExit() (which only removes EMPTY directories)
    // can't clean them up. Track them here and delete the whole tree in tearDown() instead.
    private val tempDirsToClean = mutableListOf<File>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } answers { println("DEBUG: ${args[0]}: ${args[1]}"); 0 }
        every { Log.i(any(), any()) } answers { println("INFO: ${args[0]}: ${args[1]}"); 0 }
        every { Log.w(any(), any() as String) } answers { println("WARN: ${args[0]}: ${args[1]}"); 0 }
        every { Log.e(any(), any()) } answers { println("ERROR: ${args[0]}: ${args[1]}"); 0 }
        every { Log.e(any(), any(), any()) } answers {
            println("ERROR: ${args[0]}: ${args[1]}")
            (args[2] as? Throwable)?.printStackTrace()
            0
        }
        
        every { repo.allRecordings } returns flowOf(emptyList())
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED)
        )
        
        // Mock all dependencies that would touch Android internals
        every { db.recordingDao() } returns recordingDao
        coEvery { recordingDao.get(any()) } returns null
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        
        viewModel = RecordingViewModel(
            application = application,
            db = db,
            repo = repo,
            llm = llm,
            transcriber = transcriber,
            embedder = embedder,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        tempDirsToClean.forEach { it.deleteRecursively() }
        tempDirsToClean.clear()
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
        coEvery { repo.semanticSearch(any(), any(), any(), any()) } returns recordings
        coEvery { llm.generate(any(), any<String>()) } returns answer

        viewModel.askLibraryQuestion(question)
        advanceUntilIdle()

        assertEquals(answer, viewModel.libraryAnswer.value)
        assertEquals(recordings, viewModel.librarySources.value)
        assertEquals(question, viewModel.libraryQuestion.value)
    }

    @Test
    fun askLibraryQuestion_includesGraphSiblingsInSourcesAndPrompt() = runTest {
        val question = "What about AI?"
        val answer = "Some answer"
        val embedding = floatArrayOf(0.1f, 0.2f)
        val seed = Recording("seed.mp3", title = "Seed Note", summary = "Summary", shortSummary = "Seed short summary", topics = listOf("AI"), embedding = embedding)
        val sibling = Recording("sibling.mp3", title = "Sibling Note", summary = "Summary", shortSummary = "Sibling short summary", topics = listOf("ai"), embedding = embedding)
        val unrelated = Recording("unrelated.mp3", title = "Unrelated Note", summary = "Summary", shortSummary = "Unrelated short summary", topics = listOf("Cooking"), embedding = embedding)
        val all = listOf(seed, sibling, unrelated)

        every { embedder.isReady } returns true
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)
        every { repo.allRecordings } returns flowOf(all)
        coEvery { repo.semanticSearch(any(), any(), any(), any()) } returns listOf(seed)
        coEvery { llm.generate(any(), any<String>()) } returns answer

        viewModel.askLibraryQuestion(question)
        advanceUntilIdle()

        assertEquals(listOf(seed, sibling), viewModel.librarySources.value)
        val promptSlot = slot<String>()
        coVerify { llm.generate(capture(promptSlot), question) }
        assertTrue(promptSlot.captured.contains("Sibling Note"))
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

    @Test
    fun syncAllBleFiles_tagsDownloadedRecordingWithConnectedDeviceSerial() = runTest {
        val entry = com.daedalus.notes.ble.FileEntry(filename = "device1.mp3", sizeBytes = 100L)
        coEvery { repo.getPendingDeletes() } returns emptyList()
        coEvery { bleManager.listFiles() } returns Unit
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(
                connectionState = ConnectionState.CONNECTED,
                files = listOf(entry),
                deviceSerial = "K9THA22775"
            )
        )
        coEvery { repo.get("device1.mp3") } returns null
        val tempFile = java.io.File.createTempFile("device1", ".mp3").apply { deleteOnExit() }
        coEvery { bleManager.downloadFile(eq("device1.mp3"), any()) } returns tempFile

        viewModel.syncAllBleFiles(bleManager)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repo.save(match { it.filename == "device1.mp3" && it.deviceSerial == "K9THA22775" })
        }
    }

    @Test
    fun syncAllBleFiles_blankDeviceSerial_persistsNull() = runTest {
        val entry = com.daedalus.notes.ble.FileEntry(filename = "device2.mp3", sizeBytes = 100L)
        coEvery { repo.getPendingDeletes() } returns emptyList()
        coEvery { bleManager.listFiles() } returns Unit
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(
                connectionState = ConnectionState.CONNECTED,
                files = listOf(entry),
                deviceSerial = ""
            )
        )
        coEvery { repo.get("device2.mp3") } returns null
        val tempFile = java.io.File.createTempFile("device2", ".mp3").apply { deleteOnExit() }
        coEvery { bleManager.downloadFile(eq("device2.mp3"), any()) } returns tempFile

        viewModel.syncAllBleFiles(bleManager)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repo.save(match { it.filename == "device2.mp3" && it.deviceSerial == null })
        }
    }

    @Test
    fun syncAllBleFiles_blankDeviceSerial_preservesPriorKnownSerial() = runTest {
        val entry = com.daedalus.notes.ble.FileEntry(filename = "device3.mp3", sizeBytes = 100L)
        coEvery { repo.getPendingDeletes() } returns emptyList()
        coEvery { bleManager.listFiles() } returns Unit
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(
                connectionState = ConnectionState.CONNECTED,
                files = listOf(entry),
                // This sync pass' own serial read failed/is blank, but the row was already
                // tagged by an earlier successful sync — that provenance must not be clobbered.
                deviceSerial = ""
            )
        )
        coEvery { repo.get("device3.mp3") } returns Recording("device3.mp3", deviceSerial = "K9THA22775")
        val tempFile = java.io.File.createTempFile("device3", ".mp3").apply { deleteOnExit() }
        coEvery { bleManager.downloadFile(eq("device3.mp3"), any()) } returns tempFile

        viewModel.syncAllBleFiles(bleManager)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repo.save(match { it.filename == "device3.mp3" && it.deviceSerial == "K9THA22775" })
        }
    }

    @Test
    fun wipeLocalAnalysis_callsRepoWipe() = runTest {
        var successCalled = false
        var errorMessage: String? = null
        viewModel.wipeLocalAnalysis(
            deleteLocalAudio = false,
            onSuccess = { successCalled = true },
            onError = { errorMessage = it }
        )
        
        advanceUntilIdle()
        
        coVerify(exactly = 1) { repo.wipeAllAnalysis() }
        assertNull(errorMessage, errorMessage)
        assertEquals(true, successCalled)
    }

    // A recording that can never yield a readable transcript used to be re-attempted on every
    // sync, forever — and the costly failures are the ones that fail after a full Whisper pass.
    // analysisFailed records the attempt; doAnalyze's first act is repo.get(filename), so that
    // call is the signal that an analysis was attempted at all.
    @Test
    fun autoAnalyzePending_skipsRecordingsAlreadyWrittenOffAsUnanalyzable() = runTest {
        val audio = File.createTempFile("auto-analyze", ".mp3").also { it.deleteOnExit() }
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { application.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getBoolean("auto_process", false) } returns true

        every { repo.allRecordings } returns flowOf(
            listOf(
                Recording(filename = "fresh", localPath = audio.absolutePath),
                Recording(filename = "written-off", localPath = audio.absolutePath, analysisFailed = true)
            )
        )

        viewModel.autoAnalyzePending()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.get("fresh") }
        coVerify(exactly = 0) { repo.get("written-off") }
    }

    // #150: autoAnalyzePending() snapshots blank-summary recordings, then each doAnalyze() call
    // queues behind heavyWork. A manual analyze can finish first and fill in the summary while
    // the auto-triggered call is still waiting — repo.get(filename) inside doAnalyzeExclusive is
    // the re-check under the lock, and it must see the now-non-blank summary and skip rather than
    // re-running Whisper+Gemma and overwriting the manual result.
    @Test
    fun autoAnalyzePending_skipsWhenSummaryWasFilledInWhileQueuedBehindTheLock() = runTest {
        val audio = File.createTempFile("raced-auto", ".mp3").also { it.deleteOnExit() }
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = transcriber, embedder = embedder, ioDispatcher = testDispatcher
        )
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { application.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getBoolean("auto_process", false) } returns true

        // Snapshot taken by autoAnalyzePending() still shows a blank summary...
        every { repo.allRecordings } returns flowOf(
            listOf(Recording(filename = "raced", localPath = audio.absolutePath, durationMillis = 1000))
        )
        // ...but by the time doAnalyzeExclusive's repo.get() runs (after acquiring heavyWork), a
        // manual analyze has already written a summary — simulating the queued-then-superseded race.
        coEvery { repo.get("raced") } returns Recording(
            filename = "raced", localPath = audio.absolutePath, durationMillis = 1000,
            summary = "Already summarised by the manual run that won the race"
        )

        vm.autoAnalyzePending()
        advanceUntilIdle()

        coVerify(exactly = 0) { transcriber.transcribe(any()) }
        coVerify(exactly = 0) { transcriber.transcribeRange(any(), any(), any()) }
    }

    // Companion to the skip test above: a manual (non-auto-triggered) analyze on the same
    // already-summarised recording is a deliberate re-analysis and must still run.
    @Test
    fun analyze_manualCallStillRunsEvenWhenSummaryAlreadyExists() = runTest {
        val audio = File.createTempFile("manual-reanalyze", ".mp3").also { it.deleteOnExit() }
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = transcriber, embedder = embedder, ioDispatcher = testDispatcher
        )
        coEvery { repo.get("already-summarised") } returns Recording(
            filename = "already-summarised", localPath = audio.absolutePath, durationMillis = 1000,
            summary = "Existing summary"
        )
        coEvery { transcriber.transcribe(any()) } returns "a readable transcript with enough words"

        vm.analyze("already-summarised")
        advanceUntilIdle()

        coVerify(exactly = 1) { transcriber.transcribe(any()) }
    }

    // A recording flagged unanalyzable, then re-synced with a clean transfer (local file pruned
    // in between), must be eligible for auto-analysis again — the flag described the old, bad
    // copy of the audio, and saveSyncedRecording only ever runs when fresh audio just landed.
    @Test
    fun syncAllBleFiles_freshDownloadClearsAnalysisFailedFromThePreviousCopy() = runTest {
        val entry = com.daedalus.notes.ble.FileEntry(filename = "flagged.mp3", sizeBytes = 100L)
        coEvery { repo.getPendingDeletes() } returns emptyList()
        coEvery { bleManager.listFiles() } returns Unit
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { repo.get("flagged.mp3") } returns
            Recording("flagged.mp3", analysisFailed = true)
        val tempFile = File.createTempFile("flagged", ".mp3").apply { deleteOnExit() }
        coEvery { bleManager.downloadFile(eq("flagged.mp3"), any()) } returns tempFile

        viewModel.syncAllBleFiles(bleManager)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repo.save(match { it.filename == "flagged.mp3" && !it.analysisFailed })
        }
    }

    // --- #117: a failed download must be visible, not silently swallowed ---------------------

    @Test
    fun syncAllBleFiles_failedDownload_isLoggedNotSavedAndSurfacedAsError() = runTest {
        val entry = com.daedalus.notes.ble.FileEntry(filename = "broken.mp3", sizeBytes = 100L)
        coEvery { repo.getPendingDeletes() } returns emptyList()
        coEvery { bleManager.listFiles() } returns Unit
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { repo.get("broken.mp3") } returns null
        // downloadFile returning null is the documented failure signal (BleManager deletes the
        // 0-byte file itself and returns null; see BleManagerTest for that half of the fix).
        coEvery { bleManager.downloadFile(eq("broken.mp3"), any()) } returns null

        viewModel.syncAllBleFiles(bleManager)
        advanceUntilIdle()

        // No DB row is ever created for a file whose download failed.
        coVerify(exactly = 0) { repo.save(match { it.filename == "broken.mp3" }) }
        verify(exactly = 1) {
            Log.w("DaedalusSync", match<String> { it.contains("broken.mp3") })
        }
        assertEquals(
            "1 file(s) could not be downloaded from the device and will be retried on the next sync.",
            viewModel.aiError.value
        )
    }

    @Test
    fun syncAllBleFiles_successfulDownload_summaryReportsSyncedCountAndNoError() = runTest {
        val entry = com.daedalus.notes.ble.FileEntry(filename = "ok.mp3", sizeBytes = 100L)
        coEvery { repo.getPendingDeletes() } returns emptyList()
        coEvery { bleManager.listFiles() } returns Unit
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { repo.get("ok.mp3") } returns null
        val tempFile = File.createTempFile("ok-file", ".mp3").apply { deleteOnExit() }
        coEvery { bleManager.downloadFile(eq("ok.mp3"), any()) } returns tempFile

        val progressValues = mutableListOf<String?>()
        val collectJob = launch { viewModel.syncProgress.collect { progressValues.add(it) } }

        viewModel.syncAllBleFiles(bleManager)
        advanceUntilIdle()
        collectJob.cancel()

        coVerify(exactly = 1) {
            repo.save(match { it.filename == "ok.mp3" && it.localPath == tempFile.absolutePath })
        }
        assertTrue(
            "expected a 'Synced 1 file(s)' progress message, saw: $progressValues",
            progressValues.contains("Synced 1 file(s)")
        )
        assertNull(viewModel.aiError.value)
    }

    // The generic catch around transcribeRange in the split loop breaks rather than aborting the
    // whole run, so part 1's audio getting nothing usable ends up on the same created==0 path as
    // a genuine transient exception. Only the latter is supposed to write the recording off —
    // the deliberate rule (mirrored by the flag-free catch at the bottom of doAnalyzeExclusive)
    // is that transient failures must stay retryable.
    @Test
    fun splitAnalysis_transientExceptionMidSplit_doesNotMarkAnalysisFailed() = runTest {
        val audio = File.createTempFile("split-error", ".mp3").also { it.deleteOnExit() }
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        coEvery { transcriber.transcribeRange(any(), any(), any()) } throws RuntimeException("native OOM")
        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = transcriber, embedder = embedder, ioDispatcher = testDispatcher
        )
        coEvery { repo.get("long1") } returns Recording(
            filename = "long1", localPath = audio.absolutePath, durationMillis = 20L * 60 * 1000
        )
        // Whisper reads as installed, so if the exception path fell through to
        // reportNothingReadable (the bug), it WOULD flag the recording — proving this test
        // actually exercises the distinction rather than passing for the wrong reason.
        val filesDir = java.nio.file.Files.createTempDirectory("whisper-model").toFile()
        every { application.filesDir } returns filesDir
        val whisperDir = File(File(filesDir, "models"), "sherpa-whisper-base").apply { mkdirs() }
        File(whisperDir, "base.en-encoder.int8.onnx").createNewFile()
        File(whisperDir, "base.en-decoder.int8.onnx").createNewFile()
        File(whisperDir, "base.en-tokens.txt").createNewFile()

        vm.analyze("long1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.updateAnalysisFailed("long1", true) }
    }

    // The genuine "nothing readable" case must still be remembered — only a caught exception
    // is exempt.
    @Test
    fun splitAnalysis_zeroPartsBecauseUnreadable_marksAnalysisFailed() = runTest {
        val audio = File.createTempFile("split-unreadable", ".mp3").also { it.deleteOnExit() }
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        coEvery { transcriber.transcribeRange(any(), any(), any()) } returns ""
        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = transcriber, embedder = embedder, ioDispatcher = testDispatcher
        )
        coEvery { repo.get("long2") } returns Recording(
            filename = "long2", localPath = audio.absolutePath, durationMillis = 20L * 60 * 1000
        )
        // Whisper must read as installed, or reportNothingReadable treats this as an
        // environment problem instead and deliberately does not set the flag.
        val filesDir = java.nio.file.Files.createTempDirectory("whisper-model").toFile()
        every { application.filesDir } returns filesDir
        val whisperDir = File(File(filesDir, "models"), "sherpa-whisper-base").apply { mkdirs() }
        File(whisperDir, "base.en-encoder.int8.onnx").createNewFile()
        File(whisperDir, "base.en-decoder.int8.onnx").createNewFile()
        File(whisperDir, "base.en-tokens.txt").createNewFile()

        vm.analyze("long2")
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.updateAnalysisFailed("long2", true) }
    }

    // An abort with created > 0 must still save the parent (so a blank summary doesn't make
    // autoAnalyzePending() re-split forever), but the shortSummary must say the run was
    // interrupted and give both the created and expected part counts — claiming "Split into 1
    // part" when 3 of 4 parts are missing silently truncates the recording forever.
    @Test
    fun splitAnalysis_abortedAfterSomePartsSucceed_shortSummaryConveysInterruption() = runTest {
        val audio = File.createTempFile("split-partial-abort", ".mp3").also { it.deleteOnExit() }
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        coEvery { transcriber.transcribeRange(any(), any(), any()) } returns
            "this is a readable transcript with enough words" andThenThrows
            RuntimeException("native OOM")
        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = transcriber, embedder = embedder, ioDispatcher = testDispatcher
        )
        coEvery { repo.get("long3") } returns Recording(
            filename = "long3", localPath = audio.absolutePath, durationMillis = 60L * 60 * 1000
        )
        val savedNotes = mutableListOf<Recording>()
        coEvery { repo.save(capture(savedNotes)) } answers { }

        vm.analyze("long3")
        advanceUntilIdle()

        val parentSave = savedNotes.lastOrNull { it.filename == "long3" }
        assertTrue("expected a save of the parent recording", parentSave != null)
        val summary = parentSave!!.shortSummary
        assertTrue(
            "expected shortSummary to mention interruption, was: $summary",
            summary.contains("interrupted", ignoreCase = true) ||
                summary.contains("Re-analyze", ignoreCase = true)
        )
        assertTrue("expected shortSummary to report created count (1), was: $summary", "1" in summary)
        assertTrue("expected shortSummary to report expected count (4), was: $summary", "4" in summary)
        assertTrue(
            "aborted run must not be flagged as a hard failure",
            !parentSave.analysisFailed
        )
    }

    // A clean run (no abort) must keep the existing wording unchanged.
    @Test
    fun splitAnalysis_cleanFourOfFourParts_shortSummaryUnchanged() = runTest {
        val audio = File.createTempFile("split-clean", ".mp3").also { it.deleteOnExit() }
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        coEvery { transcriber.transcribeRange(any(), any(), any()) } returns
            "this is a readable transcript with enough words"
        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = transcriber, embedder = embedder, ioDispatcher = testDispatcher
        )
        coEvery { repo.get("long4") } returns Recording(
            filename = "long4", localPath = audio.absolutePath, durationMillis = 60L * 60 * 1000
        )
        val savedNotes = mutableListOf<Recording>()
        coEvery { repo.save(capture(savedNotes)) } answers { }

        vm.analyze("long4")
        advanceUntilIdle()

        val parentSave = savedNotes.lastOrNull { it.filename == "long4" }
        assertTrue("expected a save of the parent recording", parentSave != null)
        assertEquals(
            "Split into 4 parts of ~15 min each. Tap to expand.",
            parentSave!!.shortSummary
        )
        assertTrue(!parentSave.analysisFailed)
    }

    @Test
    fun importBackup_withValidData_importsSuccessfully() = runTest {
        val uri = mockk<Uri>()
        val contentResolver = mockk<android.content.ContentResolver>()
        every { application.contentResolver } returns contentResolver
        coEvery { repo.get(any()) } returns null
        
        val tempDir = java.nio.file.Files.createTempDirectory("daedalus_test").toFile()
        every { application.getExternalFilesDir(null) } returns tempDir
        
        val json = """
            {
              "recordings": [
                {
                  "filename": "valid_recording.mp3",
                  "title": "Valid Note Title",
                  "transcript": "Hello valid import",
                  "category": 3,
                  "createdAt": 123456789,
                  "topics": ["test", "import"]
                }
              ]
            }
        """.trimIndent()
        every { contentResolver.openInputStream(uri) } returns java.io.ByteArrayInputStream(json.toByteArray())
        
        var importedCount = 0
        var errorMsg: String? = null
        
        viewModel.importBackup(
            uri = uri,
            onSuccess = { count -> importedCount = count },
            onError = { errorMsg = it }
        )
        advanceUntilIdle()
        
        println("DEBUG TEST 1: importedCount = $importedCount, errorMsg = $errorMsg")
        assertNull(errorMsg)
        assertEquals(1, importedCount)
        
        // Verify save was called with the correct recording data
        coVerify(exactly = 1) {
            recordingDao.upsert(match { recording ->
                recording.filename == "valid_recording.mp3" &&
                recording.title == "Valid Note Title" &&
                recording.transcript == "Hello valid import" &&
                recording.category == 3 &&
                recording.createdAt == 123456789L &&
                recording.topics == listOf("test", "import")
            })
        }
    }

    @Test
    fun importBackup_withFilenamePathTraversal_skipsVulnerableEntry() = runTest {
        val uri = mockk<Uri>()
        val contentResolver = mockk<android.content.ContentResolver>()
        every { application.contentResolver } returns contentResolver
        coEvery { repo.get(any()) } returns null
        
        val tempDir = java.nio.file.Files.createTempDirectory("daedalus_test").toFile()
        every { application.getExternalFilesDir(null) } returns tempDir
        
        val json = """
            {
              "recordings": [
                {
                  "filename": "../../../traversal.mp3",
                  "title": "Vulnerable Note",
                  "transcript": "Vulnerable entry"
                },
                {
                  "filename": "valid_entry.mp3",
                  "title": "Safe Note",
                  "transcript": "Safe entry"
                }
              ]
            }
        """.trimIndent()
        every { contentResolver.openInputStream(uri) } returns java.io.ByteArrayInputStream(json.toByteArray())
        
        var importedCount = 0
        var errorMsg: String? = null
        viewModel.importBackup(
            uri = uri,
            onSuccess = { count -> importedCount = count },
            onError = { errorMsg = it }
        )
        advanceUntilIdle()
        
        println("DEBUG TEST 2: importedCount = $importedCount, errorMsg = $errorMsg")
        assertNull(errorMsg)
        
        // Only the valid entry should be imported, the traversal one should be skipped
        assertEquals(1, importedCount)
        
        // Verify only safe entry saved
        coVerify(exactly = 1) {
            recordingDao.upsert(match { it.filename == "valid_entry.mp3" })
        }
        coVerify(exactly = 0) {
            recordingDao.upsert(match { it.filename.contains("traversal") })
        }
    }

    @Test
    fun importBackup_withLocalPathTraversal_ignoresVulnerableLocalPath() = runTest {
        val uri = mockk<Uri>()
        val contentResolver = mockk<android.content.ContentResolver>()
        every { application.contentResolver } returns contentResolver
        coEvery { repo.get(any()) } returns null
        
        val tempDir = java.nio.file.Files.createTempDirectory("daedalus_test").toFile()
        every { application.getExternalFilesDir(null) } returns tempDir
        
        // Create a fake target file outside our app sandbox (represented by tempDir)
        val outerTempFile = java.io.File(tempDir.parentFile, "should_not_access.mp3")
        outerTempFile.createNewFile()
        outerTempFile.deleteOnExit()
        
        val json = """
            {
              "recordings": [
                {
                  "filename": "recording_with_bad_path.mp3",
                  "localPath": "${outerTempFile.absolutePath.replace("\\", "\\\\")}",
                  "title": "Bad Local Path Note"
                }
              ]
            }
        """.trimIndent()
        every { contentResolver.openInputStream(uri) } returns java.io.ByteArrayInputStream(json.toByteArray())
        
        var importedCount = 0
        var errorMsg: String? = null
        viewModel.importBackup(
            uri = uri,
            onSuccess = { count -> importedCount = count },
            onError = { errorMsg = it }
        )
        advanceUntilIdle()
        
        println("DEBUG TEST 3: importedCount = $importedCount, errorMsg = $errorMsg")
        assertNull(errorMsg)
        assertEquals(1, importedCount)
        
        // The recording should be saved, but localPath must be empty/ignored
        coVerify(exactly = 1) {
            recordingDao.upsert(match { recording ->
                recording.filename == "recording_with_bad_path.mp3" &&
                recording.localPath.isEmpty()
            })
        }
    }

    @Test
    fun redownloadAndAnalyze_whenFileDeletedFromDevice_showsError() = runTest {
        val filename = "deleted_recording.mp3"
        coEvery { repo.get(filename) } returns Recording(filename, isLocal = false)

        val entry = com.daedalus.notes.ble.FileEntry(filename = "other_recording.mp3", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(
                connectionState = ConnectionState.CONNECTED,
                files = listOf(entry)
            )
        )
        coEvery { bleManager.listFiles() } returns Unit

        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        coVerify(exactly = 1) { bleManager.listFiles() }
        coVerify(exactly = 0) { bleManager.downloadFile(any(), any()) }
        assertEquals("Recording no longer exists on device.", viewModel.aiError.value)
    }

    @Test
    fun redownloadAndAnalyze_refreshesFileListBeforeCheckingExistence() = runTest {
        val filename = "target_recording.mp3"
        coEvery { repo.get(filename) } returns Recording(filename, isLocal = false)

        val entry = com.daedalus.notes.ble.FileEntry(filename = "target_recording", sizeBytes = 100L)
        val bleStateFlow = MutableStateFlow(
            BleState(
                connectionState = ConnectionState.CONNECTED,
                files = emptyList() // Initially stale/empty!
            )
        )
        every { bleManager.bleState } returns bleStateFlow
        coEvery { bleManager.listFiles() } answers {
            bleStateFlow.value = BleState(
                connectionState = ConnectionState.CONNECTED,
                files = listOf(entry)
            )
        }
        coEvery { bleManager.downloadFile(any(), any()) } returns null

        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        coVerify(exactly = 1) { bleManager.listFiles() }
    }

    // #119: downloadFile() returns a non-null File whenever the FW920 sent an EOF ack,
    // regardless of how many bytes actually arrived. A silently short replacement must be
    // rejected using the pre-download .bak copy as the completeness oracle, restoring it and
    // leaving the DB row/derived analysis untouched, instead of overwriting a good copy with a
    // truncated one and wiping the analysis derived from it.
    @Test
    fun redownloadAndAnalyze_shorterReplacement_rejectsAndRestoresBackup() = runTest {
        val filename = "short_replace.mp3"
        val originalContent = ByteArray(1000) { it.toByte() }
        val original = File.createTempFile("short_replace_orig", ".mp3").apply {
            writeBytes(originalContent)
            deleteOnExit()
        }
        val recording = Recording(
            filename, isLocal = false, localPath = original.absolutePath,
            transcript = "orig transcript", summary = "orig summary", mindMap = "orig map",
            title = "Orig Title", shortSummary = "orig short", topics = listOf("t1"),
            sizeBytes = originalContent.size.toLong()
        )
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "short_replace", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        // Mirrors real BleManager.downloadFile(): deletes the existing local file first, then
        // writes a shorter replacement in its place — the same path, less content — that still
        // ends with a normal EOF ack. This makes the restore assertion below meaningful: without
        // the deletion, the original content would still be sitting untouched at that path and
        // the "restored" assertion would pass trivially even with no fix in place.
        coEvery { bleManager.downloadFile(eq(filename), any()) } coAnswers {
            original.delete()
            File(original.absolutePath).apply {
                writeBytes(ByteArray(500) { it.toByte() })
            }
        }

        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        val restored = File(original.absolutePath)
        assertEquals(1000, restored.length().toInt())
        assertTrue(restored.readBytes().contentEquals(originalContent))

        coVerify(exactly = 0) { repo.save(any()) }
        coVerify(exactly = 0) { repo.deletePartsOf(any()) }
        assertTrue(
            "expected aiError to mention the short re-download, was: ${viewModel.aiError.value}",
            viewModel.aiError.value?.contains("shorter", ignoreCase = true) == true
        )
    }

    // Equal length is the ordinary successful case: the guard must not interfere with it. This
    // and the two tests below aren't regression tests for the byte-length guard itself — none of
    // them ever trigger a rejection, so they'd pass just as well without the guard's code. What
    // they cover is the ioDispatcher fix a few lines up (Dispatchers.IO -> ioDispatcher): before
    // that, mixing a real dispatcher with the StandardTestDispatcher in the same suspend chain
    // made these flaky, not the guard's accept/reject logic.
    @Test
    fun redownloadAndAnalyze_equalLengthReplacement_proceedsNormally() = runTest {
        val filename = "equal_replace.mp3"
        val original = File.createTempFile("equal_replace_orig", ".mp3").apply {
            writeBytes(ByteArray(500) { it.toByte() })
            deleteOnExit()
        }
        val recording = Recording(
            filename, isLocal = false, localPath = original.absolutePath,
            transcript = "orig transcript", sizeBytes = 500L
        )
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "equal_replace", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        val equalLength = File.createTempFile("equal_replace_downloaded", ".mp3").apply {
            writeBytes(ByteArray(500) { (it + 1).toByte() })
            deleteOnExit()
        }
        coEvery { bleManager.downloadFile(eq(filename), any()) } returns equalLength

        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        val bak = File(original.parentFile, original.name + ".bak")
        assertTrue("expected .bak to be cleaned up", !bak.exists())
        // deletePartsOf is also called independently by the doAnalyze() this triggers, so its
        // count here can be 1 or 2 depending on coroutine scheduling — assert it happened at
        // least once rather than pinning an ambiguous exact count.
        coVerify(atLeast = 1) { repo.deletePartsOf(filename) }
        // Pin the re-download's own save specifically: its localPath/sizeBytes are the
        // downloaded file's, which distinguishes it from doAnalyze()'s later save of the same
        // filename (which reads back the original recording's path/size via the mocked repo).
        coVerify(exactly = 1) {
            repo.save(match {
                it.filename == filename && it.transcript == "" &&
                    it.localPath == equalLength.absolutePath && it.sizeBytes == 500L
            })
        }
    }

    // Longer is legitimate: the previous local copy may itself have been truncated, which is
    // exactly the case this feature exists to fix, so a longer replacement must be accepted.
    @Test
    fun redownloadAndAnalyze_longerReplacement_isAccepted() = runTest {
        val filename = "longer_replace.mp3"
        val original = File.createTempFile("longer_replace_orig", ".mp3").apply {
            writeBytes(ByteArray(500) { it.toByte() })
            deleteOnExit()
        }
        val recording = Recording(
            filename, isLocal = false, localPath = original.absolutePath,
            transcript = "orig transcript", sizeBytes = 500L
        )
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "longer_replace", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        val longer = File.createTempFile("longer_replace_downloaded", ".mp3").apply {
            writeBytes(ByteArray(1500) { it.toByte() })
            deleteOnExit()
        }
        coEvery { bleManager.downloadFile(eq(filename), any()) } returns longer

        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        val bak = File(original.parentFile, original.name + ".bak")
        assertTrue("expected .bak to be cleaned up", !bak.exists())
        // deletePartsOf is also called independently by the doAnalyze() this triggers, so its
        // count here can be 1 or 2 depending on coroutine scheduling — assert it happened at
        // least once rather than pinning an ambiguous exact count.
        coVerify(atLeast = 1) { repo.deletePartsOf(filename) }
        // Pin the re-download's own save specifically: its localPath/sizeBytes are the
        // downloaded file's, which distinguishes it from doAnalyze()'s later save of the same
        // filename (which reads back the original recording's path/size via the mocked repo).
        coVerify(exactly = 1) {
            repo.save(match {
                it.filename == filename && it.transcript == "" &&
                    it.localPath == longer.absolutePath && it.sizeBytes == 1500L
            })
        }
    }

    // No prior local copy means there's nothing to compare against — a fresh fetch must always
    // be accepted regardless of size.
    @Test
    fun redownloadAndAnalyze_noPriorLocalCopy_isAccepted() = runTest {
        val filename = "fresh_fetch.mp3"
        val missingLocalPath = File.createTempFile("fresh_fetch_missing", ".mp3").let {
            it.delete() // ensure it does not exist
            it.absolutePath
        }
        val recording = Recording(filename, isLocal = false, localPath = missingLocalPath)
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "fresh_fetch", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        val downloaded = File.createTempFile("fresh_fetch_downloaded", ".mp3").apply {
            writeBytes(ByteArray(10) { it.toByte() })
            deleteOnExit()
        }
        coEvery { bleManager.downloadFile(eq(filename), any()) } returns downloaded

        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.deletePartsOf(filename) }
        coVerify(exactly = 1) { repo.save(match { it.filename == filename && it.transcript == "" }) }
    }

    // Regression for HIGH-1: restoreBackup() must not delete the .bak when the restore copy
    // itself fails. copyTo(overwrite = true) deletes the destination before it writes, so if the
    // copy throws partway through, the .bak is the only remaining good copy and must survive.
    // Forces a failure by making the download replace the local path with a directory (real,
    // deterministic on every platform) before triggering the downloaded == null restore path.
    @Test
    fun restoreBackup_copyFailurePreservesBackup() = runTest {
        val filename = "restore_fail.mp3"
        val originalContent = ByteArray(1000) { it.toByte() }
        val original = File.createTempFile("restore_fail_orig", ".mp3").apply {
            writeBytes(originalContent)
            deleteOnExit()
        }
        val recording = Recording(
            filename, isLocal = false, localPath = original.absolutePath,
            sizeBytes = originalContent.size.toLong()
        )
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "restore_fail", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        // Simulates a transfer that deletes the original and then fails so badly that the local
        // path can no longer be written back to (e.g. media unmounted mid-transfer): downloadFile
        // returns null (a failed transfer), and copyTo(localPath) will throw because localPath
        // now resolves to a non-empty directory instead of a file — copyTo's overwrite path
        // calls target.delete() first, which fails (returns false, doesn't throw) for a
        // non-empty directory, so copyTo throws FileAlreadyExistsException rather than silently
        // succeeding the way it would against an empty directory.
        coEvery { bleManager.downloadFile(eq(filename), any()) } coAnswers {
            original.delete()
            File(original.absolutePath).mkdirs()
            File(original.absolutePath, "lock.txt").writeText("occupied")
            null
        }

        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        val bak = File(original.parentFile, original.name + ".bak")
        assertTrue("expected .bak to survive a failed restore", bak.exists())
        assertEquals(1000, bak.length().toInt())
        coVerify(exactly = 0) { repo.save(any()) }
    }

    // Regression for HIGH-2a: the guard must compare against the length captured at backup
    // creation time, not a live re-read of the .bak file. File.length() on a missing file
    // silently returns 0, which would make any truncated download look "not shorter" and let it
    // straight through if the guard re-read the file instead of using a captured value.
    @Test
    fun redownloadAndAnalyze_vanishedBackup_stillRejectsShortReplacement() = runTest {
        val filename = "vanished_bak.mp3"
        val originalContent = ByteArray(1000) { it.toByte() }
        val original = File.createTempFile("vanished_bak_orig", ".mp3").apply {
            writeBytes(originalContent)
            deleteOnExit()
        }
        val recording = Recording(
            filename, isLocal = false, localPath = original.absolutePath,
            sizeBytes = originalContent.size.toLong()
        )
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "vanished_bak", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        // Deletes the .bak the guard would otherwise have to re-read live, then writes a shorter
        // replacement at the local path.
        coEvery { bleManager.downloadFile(eq(filename), any()) } coAnswers {
            val bak = File(original.parentFile, original.name + ".bak")
            assertTrue("expected .bak to exist before the test deletes it", bak.exists())
            bak.delete()
            original.delete()
            File(original.absolutePath).apply { writeBytes(ByteArray(500) { it.toByte() }) }
        }

        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.save(any()) }
        coVerify(exactly = 0) { repo.deletePartsOf(any()) }
        assertTrue(
            "expected aiError to mention the short re-download, was: ${viewModel.aiError.value}",
            viewModel.aiError.value?.contains("shorter", ignoreCase = true) == true
        )
    }

    // Re-download requested from a part must resolve to the parent's filename for both the BLE
    // fetch and the guard's completeness check (the part itself has no file on the device).
    @Test
    fun redownloadAndAnalyze_requestedFromPart_resolvesParentAndAppliesGuard() = runTest {
        val parentFilename = "part_parent.mp3"
        val partFilename = "part_parent_part1.mp3"
        val originalContent = ByteArray(1000) { it.toByte() }
        val original = File.createTempFile("part_parent_orig", ".mp3").apply {
            writeBytes(originalContent)
            deleteOnExit()
        }
        val parentRecording = Recording(
            parentFilename, isLocal = false, localPath = original.absolutePath,
            sizeBytes = originalContent.size.toLong()
        )
        val partRecording = Recording(
            partFilename, isLocal = false, parentFilename = parentFilename, partIndex = 1
        )
        coEvery { repo.get(partFilename) } returns partRecording
        coEvery { repo.get(parentFilename) } returns parentRecording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "part_parent", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        coEvery { bleManager.downloadFile(eq(parentFilename), any()) } coAnswers {
            original.delete()
            File(original.absolutePath).apply { writeBytes(ByteArray(500) { it.toByte() }) }
        }

        viewModel.redownloadAndAnalyze(partFilename, bleManager)
        advanceUntilIdle()

        val restored = File(original.absolutePath)
        assertEquals(1000, restored.length().toInt())
        assertTrue(restored.readBytes().contentEquals(originalContent))
        coVerify(exactly = 0) { repo.save(any()) }
        coVerify(exactly = 1) { bleManager.downloadFile(eq(parentFilename), any()) }
        coVerify(exactly = 0) { bleManager.downloadFile(eq(partFilename), any()) }
    }

    // --- #122: heavyWork must cover the whole re-download critical section, not just the
    // transfer, or a concurrent sync can interleave into a re-download's restore/save. ---------

    // Property proven directly: heavyWork is actually held (not just intended to be held)
    // across the whole critical section, not released between the transfer and the DB write.
    // A concurrent sync attempting to download an unrelated file must be blocked from even
    // starting until the re-download's critical section — including its repo.save — is done.
    @Test
    fun redownloadAndAnalyze_holdsHeavyWorkAcrossCriticalSection_blocksConcurrentSync() = runTest {
        val filename = "locked_target.mp3"
        val original = File.createTempFile("locked_target_orig", ".mp3").apply {
            writeBytes(ByteArray(100) { it.toByte() })
            deleteOnExit()
        }
        val recording = Recording(filename, isLocal = false, localPath = original.absolutePath, sizeBytes = 100L)
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "locked_target", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        // The transfer itself completes immediately; the gate sits on repo.deletePartsOf(),
        // which runs AFTER the transfer, as the critical section's DB write is starting. This
        // is the part that widening heavyWork is actually about: a lock held only around the
        // transfer would already have been released by this point, letting a concurrent sync
        // start downloading. Gating here — not on the transfer — is what makes this test able
        // to distinguish the fix from the pre-fix code.
        coEvery { bleManager.downloadFile(eq(filename), any()) } returns
            File(original.absolutePath).apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val gate = CompletableDeferred<Unit>()
        coEvery { repo.deletePartsOf(filename) } coAnswers { gate.await() }

        viewModel.redownloadAndAnalyze(filename, bleManager)
        // Runs the transfer to completion and up to the point the re-download is parked
        // awaiting the gate inside repo.deletePartsOf() — still inside heavyWork.
        runCurrent()

        // A concurrent sync pass for a different file must not be able to start its own
        // download while the re-download still holds heavyWork.
        coEvery { repo.getPendingDeletes() } returns emptyList()
        val otherEntry = com.daedalus.notes.ble.FileEntry(filename = "other_during_lock.mp3", sizeBytes = 50L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(otherEntry))
        )
        coEvery { repo.get("other_during_lock.mp3") } returns null
        val otherTemp = File.createTempFile("other_during_lock", ".mp3").apply { deleteOnExit() }
        coEvery { bleManager.downloadFile(eq("other_during_lock.mp3"), any()) } returns otherTemp

        viewModel.syncAllBleFiles(bleManager)
        runCurrent()

        coVerify(exactly = 0) { bleManager.downloadFile(eq("other_during_lock.mp3"), any()) }

        // Release the re-download's transfer; both its own DB write and the now-freed sync
        // must be able to proceed.
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { bleManager.downloadFile(eq("other_during_lock.mp3"), any()) }
    }

    // Property proven directly: no deadlock. doAnalyze() re-acquires heavyWork itself, so if
    // the widened lock in redownloadAndAnalyze were still held when doAnalyze() runs, this
    // would hang forever instead of completing — advanceUntilIdle() would simply leave the
    // coroutine parked, and the coVerify below would fail with "was not called".
    @Test
    fun redownloadAndAnalyze_completesAndReachesAnalysis_noDeadlock() = runTest {
        val filename = "redl_full.mp3"
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        coEvery { transcriber.transcribe(any()) } returns "a readable transcript with enough words"
        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = transcriber, embedder = embedder, ioDispatcher = testDispatcher
        )
        val original = File.createTempFile("redl_full_orig", ".mp3").apply {
            writeBytes(ByteArray(100) { it.toByte() })
            deleteOnExit()
        }
        val recording = Recording(
            filename, isLocal = false, localPath = original.absolutePath,
            sizeBytes = 100L, durationMillis = 500L
        )
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "redl_full", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        val downloaded = File.createTempFile("redl_full_downloaded", ".mp3").apply {
            writeBytes(ByteArray(150) { it.toByte() })
            deleteOnExit()
        }
        coEvery { bleManager.downloadFile(eq(filename), any()) } returns downloaded

        vm.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        // doAnalyze() actually ran to completion — the regression test for the deadlock risk.
        coVerify(exactly = 1) { transcriber.transcribe(any()) }
        assertEquals(false, vm.isProcessing.value)
        assertEquals(null, vm.syncProgress.value)
    }

    // Property proven directly: an early exit taken from *inside* heavyWork.withLock (the
    // #119 short-replacement rejection, a plain `return@launch` non-local return) still
    // releases the mutex. If it didn't, a later attempt to acquire heavyWork would never
    // proceed and the coVerify below would fail with "was not called".
    @Test
    fun redownloadAndAnalyze_shorterReplacementRejection_releasesHeavyWork() = runTest {
        val filename = "reject_release.mp3"
        val originalContent = ByteArray(1000) { it.toByte() }
        val original = File.createTempFile("reject_release_orig", ".mp3").apply {
            writeBytes(originalContent)
            deleteOnExit()
        }
        val recording = Recording(
            filename, isLocal = false, localPath = original.absolutePath,
            sizeBytes = originalContent.size.toLong()
        )
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "reject_release", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit
        coEvery { bleManager.downloadFile(eq(filename), any()) } coAnswers {
            original.delete()
            File(original.absolutePath).apply { writeBytes(ByteArray(500) { it.toByte() }) }
        }

        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        assertTrue(
            "expected the rejection to have happened first",
            viewModel.aiError.value?.contains("shorter", ignoreCase = true) == true
        )

        coEvery { repo.getPendingDeletes() } returns emptyList()
        val otherEntry = com.daedalus.notes.ble.FileEntry(filename = "after_reject.mp3", sizeBytes = 10L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(otherEntry))
        )
        coEvery { repo.get("after_reject.mp3") } returns null
        val otherTemp = File.createTempFile("after_reject", ".mp3").apply { deleteOnExit() }
        coEvery { bleManager.downloadFile(eq("after_reject.mp3"), any()) } returns otherTemp

        viewModel.syncAllBleFiles(bleManager)
        advanceUntilIdle()

        coVerify(exactly = 1) { bleManager.downloadFile(eq("after_reject.mp3"), any()) }
    }

    // Property proven directly: two concurrent re-downloads of the same file. The second call,
    // made while the first is still inside heavyWork awaiting its transfer, must be rejected
    // outright with a visible error rather than silently queued behind the lock — see the
    // inFlightRedownloads doc comment for why queuing behind the lock isn't safe here.
    @Test
    fun redownloadAndAnalyze_concurrentSameFile_secondRejectedWithError() = runTest {
        val filename = "concurrent_target.mp3"
        val original = File.createTempFile("concurrent_target_orig", ".mp3").apply {
            writeBytes(ByteArray(100) { it.toByte() })
            deleteOnExit()
        }
        val recording = Recording(filename, isLocal = false, localPath = original.absolutePath, sizeBytes = 100L)
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "concurrent_target", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        val gate = CompletableDeferred<Unit>()
        coEvery { bleManager.downloadFile(eq(filename), any()) } coAnswers {
            gate.await()
            File(original.absolutePath).apply { writeBytes(ByteArray(100) { it.toByte() }) }
        }

        viewModel.redownloadAndAnalyze(filename, bleManager)
        runCurrent()

        // Second call for the same file while the first is still in flight — must be
        // rejected immediately, not queued behind heavyWork.
        viewModel.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        assertEquals(
            "Already re-downloading this recording — please wait for it to finish.",
            viewModel.aiError.value
        )
        coVerify(exactly = 1) { bleManager.downloadFile(eq(filename), any()) }

        gate.complete(Unit)
        advanceUntilIdle()
    }

    // Regression test for the fix moving inFlightRedownloads.remove(filename) into the inner
    // finally (alongside _isProcessing reset) instead of the outer one that also wrapped
    // doAnalyze()/loadNote(). Drives a re-download all the way to completion, then issues a
    // second, non-concurrent re-download of the same filename and asserts it is accepted rather
    // than rejected with "already re-downloading". If `remove` were reached late — or not at
    // all — this fails with the rejection error instead of a second downloadFile() call.
    @Test
    fun redownloadAndAnalyze_secondCallAfterFirstFullyCompletes_isNotRejected() = runTest {
        val filename = "redl_twice.mp3"
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        coEvery { transcriber.transcribe(any()) } returns "a readable transcript with enough words"
        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = transcriber, embedder = embedder, ioDispatcher = testDispatcher
        )
        val original = File.createTempFile("redl_twice_orig", ".mp3").apply {
            writeBytes(ByteArray(100) { it.toByte() })
            deleteOnExit()
        }
        val recording = Recording(
            filename, isLocal = false, localPath = original.absolutePath,
            sizeBytes = 100L, durationMillis = 500L
        )
        coEvery { repo.get(filename) } returns recording

        val entry = com.daedalus.notes.ble.FileEntry(filename = "redl_twice", sizeBytes = 100L)
        every { bleManager.bleState } returns MutableStateFlow(
            BleState(connectionState = ConnectionState.CONNECTED, files = listOf(entry))
        )
        coEvery { bleManager.listFiles() } returns Unit

        val downloaded = File.createTempFile("redl_twice_downloaded", ".mp3").apply {
            writeBytes(ByteArray(150) { it.toByte() })
            deleteOnExit()
        }
        coEvery { bleManager.downloadFile(eq(filename), any()) } returns downloaded

        vm.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()
        assertEquals(null, vm.aiError.value)

        // First re-download fully completed — including doAnalyze() and loadNote() — before
        // this second call starts, so it is not concurrent with the first.
        vm.redownloadAndAnalyze(filename, bleManager)
        advanceUntilIdle()

        assertEquals(
            "the second, non-concurrent re-download must not be rejected as already in flight",
            null,
            vm.aiError.value
        )
        coVerify(exactly = 2) { bleManager.downloadFile(eq(filename), any()) }
    }

    @Test
    fun loadNote_populatesCurrentScanResult() = runTest {
        val audio = File.createTempFile("scan-test", ".mp3").also { it.deleteOnExit() }
        val filename = "scan-test.mp3"
        coEvery { repo.get(filename) } returns Recording(filename, localPath = audio.absolutePath)
        
        // Write a fake mp3 frame so scan doesn't return 0
        audio.writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()))

        viewModel.loadNote(filename)
        advanceUntilIdle()
        
        val result = viewModel.currentScanResult.value
        assertTrue(result != null)
    }

    @Test
    fun analyze_startsAndStopsForegroundServiceResetsStateOnFinish() = runTest {
        val audio = File.createTempFile("fg-service", ".mp3").also { it.deleteOnExit() }
        val filename = "fg-service.mp3"
        coEvery { repo.get(filename) } returns Recording(filename, localPath = audio.absolutePath, durationMillis = 1000L)

        viewModel.analyze(filename)
        advanceUntilIdle()

        assertEquals(false, viewModel.isProcessing.value)
        assertEquals(null, viewModel.syncProgress.value)
    }

    // ------------------------------------------------------------------
    // #136 — dispatcher routing. A method that ignores the injected `ioDispatcher` and uses a
    // raw `Dispatchers.IO` internally will dispatch its IO work onto the real IO thread pool
    // instead of the StandardTestDispatcher, so advanceUntilIdle() cannot drain it and the
    // coroutine can outlive the test — the mechanism behind #136's intermittent
    // UncaughtExceptionsBeforeTest failures. These tests pin ROUTING (did the work dispatch
    // through the injected dispatcher at all?), which is deterministic, rather than timing.
    // ------------------------------------------------------------------

    @Test
    fun init_healsMissingDurations_routesOnInjectedIoDispatcher() = runTest {
        val counting = DispatchCountingDispatcher(testDispatcher)
        val needsHeal = Recording(filename = "needs-heal.mp3", localPath = "", durationMillis = 0L, createdAt = 999L)
        every { repo.allRecordings } returns flowOf(listOf(needsHeal))

        RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = mockk(relaxed = true), embedder = embedder, ioDispatcher = counting
        )
        advanceUntilIdle()

        assertTrue(
            "init's duration/date heal must route through the injected ioDispatcher, not a real Dispatchers.IO thread",
            counting.dispatchCount > 0
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Test
    fun fullAutoSync_scansVolumes_routesOnInjectedIoDispatcher() = runTest {
        val counting = DispatchCountingDispatcher(testDispatcher)
        val storageManager = mockk<android.os.storage.StorageManager>(relaxed = true)
        every { application.getSystemService(android.os.storage.StorageManager::class.java) } returns storageManager
        every { storageManager.storageVolumes } returns emptyList()
        every { application.getExternalFilesDir(null) } returns
            java.nio.file.Files.createTempDirectory("d136_fullautosync").toFile().also { tempDirsToClean += it }

        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = mockk(relaxed = true), embedder = embedder, ioDispatcher = counting
        )
        advanceUntilIdle()
        val before = counting.dispatchCount

        vm.fullAutoSync()
        advanceUntilIdle()

        assertTrue(
            "fullAutoSync's volume scan must route through the injected ioDispatcher, not a real Dispatchers.IO thread",
            counting.dispatchCount > before
        )
    }

    @Test
    fun syncFiles_processesUris_routesOnInjectedIoDispatcher() = runTest {
        val counting = DispatchCountingDispatcher(testDispatcher)
        every { application.getExternalFilesDir(null) } returns
            java.nio.file.Files.createTempDirectory("d136_syncfiles").toFile().also { tempDirsToClean += it }

        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = mockk(relaxed = true), embedder = embedder, ioDispatcher = counting
        )
        advanceUntilIdle()
        val before = counting.dispatchCount

        // NOTE: passes an empty URI list, so this only pins entry into `withContext(ioDispatcher)`,
        // not the copy loop body (DocumentFile.fromSingleUri is a static call that would require
        // heavy mocking, plus the mocked InputStream.read() risks looping forever in copyTo). Per
        // the review finding, this gap is intentionally left uncovered and called out here.
        vm.syncFiles(emptyList())
        advanceUntilIdle()

        assertTrue(
            "syncFiles' copy loop must route through the injected ioDispatcher, not a real Dispatchers.IO thread",
            counting.dispatchCount > before
        )
    }

    @Test
    fun doAnalyzeExclusive_healsMissingDuration_routesOnInjectedIoDispatcher() = runTest {
        val counting = DispatchCountingDispatcher(testDispatcher)
        val audio = File.createTempFile("route-heal", ".mp3").also { it.deleteOnExit() }
        val filename = "needs-duration.mp3"
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        val vm = RecordingViewModel(
            application = application, db = db, repo = repo, llm = llm,
            transcriber = transcriber, embedder = embedder, ioDispatcher = counting
        )
        coEvery { repo.get(filename) } returns Recording(
            filename = filename, localPath = audio.absolutePath, durationMillis = 0L
        )
        advanceUntilIdle()
        val before = counting.dispatchCount

        vm.analyze(filename)
        advanceUntilIdle()

        assertTrue(
            "doAnalyzeExclusive's duration heal must route through the injected ioDispatcher, not a real Dispatchers.IO thread",
            counting.dispatchCount > before
        )
    }
}
