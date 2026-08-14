package com.daedalus.notes.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalus.notes.ai.AnalysisForegroundService
import com.daedalus.notes.ai.analyzeTranscript
import com.daedalus.notes.ai.aiTextBudget
import com.daedalus.notes.ai.buildLibraryQuestionPrompt
import com.daedalus.notes.ai.buildNoteQuestionPrompt
import com.daedalus.notes.ai.expandWithTopicSiblings
import com.daedalus.notes.ai.EmbeddingService
import com.daedalus.notes.ai.LocalLlmService
import com.daedalus.notes.ai.MarkdownExporter
import com.daedalus.notes.ai.TranscriptFormatter
import com.daedalus.notes.ai.TranscriptionService
import com.daedalus.notes.ai.isWhisperReady
import com.daedalus.notes.ai.isTranscriptReadable
import com.daedalus.notes.ai.selectedModel
import com.daedalus.notes.ble.BleManager
import com.daedalus.notes.ble.ConnectionState
import com.daedalus.notes.data.RecordingRepository
import com.daedalus.notes.data.backup.BackupManager
import com.daedalus.notes.data.db.AppDatabase
import com.daedalus.notes.data.model.AudioUtils
import com.daedalus.notes.data.model.DateUtils
import com.daedalus.notes.data.model.Mp3FrameScan
import com.daedalus.notes.data.model.Mp3ScanResult
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.recording.AudioRecorder
import com.daedalus.notes.ui.mindmap.GlobalGraph
import com.daedalus.notes.ui.mindmap.GraphBuilder
import com.daedalus.notes.util.SafeFilename
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SharedPreferences key for the max phone-mic recording duration, in minutes. */
const val MAX_RECORDING_MINUTES_KEY = "max_recording_minutes"
const val MAX_RECORDING_MINUTES_DEFAULT = 120

/** Sentinel value meaning "no cap" — mirrors the -1 "all recordings" convention used by [TODO_LOOKBACK_HOURS_KEY]. */
const val MAX_RECORDING_MINUTES_UNLIMITED = -1

/** Recordings longer than this are split into parts, each transcribed and analyzed independently. */
internal const val PART_DURATION_MS = 15L * 60 * 1000  // 15 minutes

/** Sane bounds for [RecordingViewModel.setPlaybackSpeed] — covers the UI's 1.0x-2.0x toggle
 *  range with headroom, while rejecting nonsense values like a stray ADB `--ef speed 500`. */
private const val MIN_PLAYBACK_SPEED = 0.25f
private const val MAX_PLAYBACK_SPEED = 4.0f

/** Titles the split path generates itself; matching ones are regenerated, not preserved. */
private val SPLIT_PLACEHOLDER_TITLE = Regex("""Long Recording( \(\d+ parts?\))?""")

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModel @JvmOverloads constructor(
    application: Application,
    private val db: AppDatabase = AppDatabase.getInstance(application),
    private val repo: RecordingRepository = RecordingRepository(db.recordingDao()),
    private val llm: LocalLlmService = LocalLlmService.getInstance(application),
    private val transcriber: TranscriptionService = TranscriptionService(application),
    private val embedder: EmbeddingService = EmbeddingService(application),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val audioRecorderProvider: () -> AudioRecorder = { AudioRecorder(application) },
    private val timerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) : AndroidViewModel(application) {

    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress

    private var syncJob: Job? = null

    /**
     * Auto-analysis runs outside [syncJob] so cancelling a sync doesn't discard a multi-part
     * analysis already minutes deep — but it is tracked here so the user can still stop it,
     * and so a second sync doesn't start a duplicate pass over recordings the first is
     * already working through.
     */
    private var autoAnalyzeJob: Job? = null

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        autoAnalyzeJob?.cancel()
        autoAnalyzeJob = null
        _syncProgress.value = null
    }

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError

    private val _isAsking = MutableStateFlow(false)
    val isAsking: StateFlow<Boolean> = _isAsking

    private val _askAnswer = MutableStateFlow<String?>(null)
    val askAnswer: StateFlow<String?> = _askAnswer

    private val _libraryAnswer = MutableStateFlow<String?>(null)
    val libraryAnswer: StateFlow<String?> = _libraryAnswer

    private val _librarySources = MutableStateFlow<List<Recording>>(emptyList())
    val librarySources: StateFlow<List<Recording>> = _librarySources

    private val _libraryQuestion = MutableStateFlow("")
    val libraryQuestion: StateFlow<String> = _libraryQuestion

    private val _currentNote = MutableStateFlow<Recording?>(null)
    val currentNote: StateFlow<Recording?> = _currentNote

    private val _currentScanResult = MutableStateFlow<Mp3ScanResult?>(null)
    val currentScanResult: StateFlow<Mp3ScanResult?> = _currentScanResult

    private val _exportIntent = MutableStateFlow<Intent?>(null)
    val exportIntent: StateFlow<Intent?> = _exportIntent

    // Local audio recording (phone mic) — fallback when no FW920 is connected.
    // Lazy so construction doesn't touch AudioManager until a recording actually starts.
    private val audioRecorder by lazy { audioRecorderProvider() }
    private var recordingTimerJob: Job? = null
    private var currentRecordingFile: File? = null

    /** Max duration cap for the recording in progress, in seconds; null means unlimited. */
    private var maxDurationCapSeconds: Long? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds

    private val _autoStopNotice = MutableStateFlow<String?>(null)
    val autoStopNotice: StateFlow<String?> = _autoStopNotice

    fun clearAutoStopNotice() { _autoStopNotice.value = null }

    val useBluetoothMic = MutableStateFlow(false)

    /**
     * Serializes the two CPU/radio-heavy jobs — BLE transfers and transcription/analysis — so
     * only one runs at a time.
     *
     * Two analyses at once exhausted the heap (each holds a full PCM window plus its own Whisper
     * recognizer). And the FW920 download protocol has no retransmission, sequence numbers or
     * integrity check, so GATT notifications dropped while Whisper and Gemma saturate the CPU
     * become silently corrupt audio that nothing downstream can detect.
     *
     * Acquire around one unit of work only — never hold this across a call that re-acquires it
     * (doAnalyze() and, transitively, analyze()/autoAnalyzePending() all acquire it themselves).
     * redownloadAndAnalyze() is the one caller that holds it across more than just the transfer
     * itself: backup creation, the BLE transfer, the #119 length guard, and the resulting
     * restore-or-DB-write, all as one critical section — not several separate BLE transfers — so
     * that a sync can't interleave a downloadFile() of the same filename into the middle of a
     * re-download's restore/save. See the comment at its heavyWork.withLock call for the cost.
     */
    private val heavyWork = Mutex()

    val allRecordings: StateFlow<List<Recording>> = repo.allRecordings
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val globalGraph: StateFlow<GlobalGraph> = allRecordings
        .map { GraphBuilder.build(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, GlobalGraph(emptyList(), emptyList()))

    /** Filenames of recordings that were split, so the list can show the expand affordance
     *  without querying per row. */
    val parentsWithParts: StateFlow<Set<String>> = repo.parentsWithParts
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Shared playback speed for NoteDetailScreen's ExoPlayer — lets the debug-only
    // com.daedalus.notes.SET_SPEED ADB trigger (MainActivity) drive the same player the UI's
    // speed toggle controls, since both go through this single ViewModel instance.
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    /** Clamped so a stray ADB value (e.g. `--ef speed 500`) can't set a nonsense rate that then
     *  persists into normal UI playback until the user manually cycles the speed toggle. */
    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
    }

    val filteredRecordings: StateFlow<List<Recording>> = _searchQuery
        .flatMapLatest { q ->
            if (q.isBlank()) repo.allRecordings else repo.search(q)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        useBluetoothMic.value = application
            .getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .getBoolean("use_bluetooth_mic", false)

        // Heal missing durations and timestamps for already synced files
        viewModelScope.launch(ioDispatcher) {
            repo.allRecordings.first().forEach { recording ->
                val dateMillis = DateUtils.parseEpochMillisFromFilename(recording.filename)
                val duration = if (recording.durationMillis == 0L && recording.localPath.isNotBlank()) {
                    AudioUtils.getDurationMillis(recording.localPath)
                } else recording.durationMillis

                if (duration != recording.durationMillis || recording.createdAt != dateMillis) {
                    repo.save(recording.copy(
                        durationMillis = duration,
                        createdAt = dateMillis
                    ))
                }
            }
        }
    }

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    // ------------------------------------------------------------------
    // Local recording (phone mic) — fallback when no FW920 is connected
    // ------------------------------------------------------------------

    fun setUseBluetoothMic(enabled: Boolean) {
        useBluetoothMic.value = enabled
        getApplication<Application>()
            .getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("use_bluetooth_mic", enabled)
            .apply()
    }

    fun startLocalRecording() {
        if (_isRecording.value) return
        val context = getApplication<Application>()
        val dir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val file = File(dir, "${sdf.format(Date())}.m4a")
        currentRecordingFile = file

        _recordingDurationSeconds.value = 0L
        maxDurationCapSeconds = readMaxDurationCapSeconds()

        try {
            audioRecorder.start(file, useBluetoothMic.value)
            _isRecording.value = true
            _isPaused.value = false
            _aiError.value = null

            recordingTimerJob?.cancel()
            recordingTimerJob = launchTimerLoop(initialElapsed = 0L)
            Log.i("RecordingViewModel", "Started local recording: ${file.name}")
        } catch (e: Exception) {
            Log.e("RecordingViewModel", "Failed to start local recording", e)
            _aiError.value = "Failed to start recording: ${e.message}"
            _isRecording.value = false
            currentRecordingFile = null
        }
    }

    fun pauseLocalRecording() {
        if (!_isRecording.value || _isPaused.value) return
        audioRecorder.pause()
        _isPaused.value = true
        recordingTimerJob?.cancel()
    }

    fun resumeLocalRecording() {
        if (!_isRecording.value || !_isPaused.value) return
        audioRecorder.resume()
        _isPaused.value = false
        recordingTimerJob = launchTimerLoop(initialElapsed = _recordingDurationSeconds.value)
    }

    /** Reads the configured max-duration cap in seconds, or null if unlimited. */
    private fun readMaxDurationCapSeconds(): Long? {
        val prefs = getApplication<Application>().getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        val minutes = prefs.getInt(MAX_RECORDING_MINUTES_KEY, MAX_RECORDING_MINUTES_DEFAULT)
        return if (minutes <= 0) null else minutes * 60L
    }

    /**
     * Ticks recordingDurationSeconds once per second starting from [initialElapsed]. When
     * maxDurationCapSeconds is reached, auto-stops through the same path a manual stop
     * uses and surfaces a notice for the UI to show.
     */
    private fun launchTimerLoop(initialElapsed: Long): Job = viewModelScope.launch(timerDispatcher) {
        var elapsed = initialElapsed
        while (true) {
            delay(1000)
            elapsed++
            _recordingDurationSeconds.value = elapsed
            val cap = maxDurationCapSeconds
            if (cap != null && elapsed >= cap) {
                // Only claim an auto-stop if the recording is still running — a manual stop
                // landing on the same tick would otherwise surface a bogus notice.
                if (_isRecording.value) {
                    _autoStopNotice.value = "Recording auto-stopped after reaching the ${cap / 60}-minute limit"
                    stopLocalRecording()
                }
                break
            }
        }
    }

    fun stopLocalRecording() {
        if (!_isRecording.value) return
        audioRecorder.stop()
        recordingTimerJob?.cancel()
        _isRecording.value = false
        _isPaused.value = false

        val file = currentRecordingFile ?: return
        currentRecordingFile = null
        if (file.exists() && file.length() > 0) {
            // Use the elapsed-seconds timer (which already excludes paused time) as the
            // source of truth, not wall-clock time — a paused-then-resumed recording would
            // otherwise report a duration longer than its actual audio. Second-granularity
            // is the timer's native precision, which is fine here.
            val duration = _recordingDurationSeconds.value * 1000L
            val name = file.name
            viewModelScope.launch {
                repo.save(
                    Recording(
                        filename = name,
                        localPath = file.absolutePath,
                        sizeBytes = file.length(),
                        durationMillis = duration,
                        createdAt = System.currentTimeMillis(),
                        isLocal = true
                    )
                )
                Log.i("RecordingViewModel", "Saved local recording: $name (${file.length()} bytes)")

                val prefs = getApplication<Application>()
                    .getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("auto_process", false)) {
                    // autoTriggered: this queues behind heavyWork just like autoAnalyzePending()'s
                    // calls, so it's exposed to the same queued-then-superseded race (#150) if a
                    // manual analyze of this recording wins the lock first.
                    doAnalyze(name, autoTriggered = true)
                }
            }
        }
    }

    fun syncAllBleFiles(bleManager: BleManager) {
        syncJob = viewModelScope.launch {
            try {
            if (!isBleConnected(bleManager)) {
                _aiError.value = "Device not connected. Connect the FW920 before syncing."
                return@launch
            }

            // Process any pending deletions while connected
            val pending = repo.getPendingDeletes()
            if (pending.isNotEmpty()) {
                var delCount = 0
                val totalDel = pending.size
                for (recording in pending) {
                    delCount++
                    _syncProgress.value = "Deleting pending $delCount of $totalDel..."
                    val success = bleManager.deleteFile(recording.filename)
                    if (success) {
                        repo.delete(recording)
                    } else {
                        Log.w("RecordingViewModel", "Failed to delete pending file ${recording.filename} from hardware during sync")
                    }
                }
            }

            _syncProgress.value = "Listing files on device…"
            bleManager.listFiles()
            val files = bleManager.bleState.value.files
            // Read once and reuse for every file downloaded this sync pass — all of them
            // come from the same connected FW920.
            val deviceSerial = bleManager.bleState.value.deviceSerial.takeIf { it.isNotBlank() }
            if (files.isEmpty()) {
                _syncProgress.value = "No files on device"
                delay(1000)
                _syncProgress.value = null
                return@launch
            }
            _aiError.value = null
            var synced = 0
            var failed = 0
            val newFilenames = mutableListOf<String>()

            // One transfer at a time, and never while an analysis is running — dropped GATT
            // notifications during CPU saturation land as silently corrupt audio. Released
            // before autoAnalyzePending() below, which re-acquires per recording.
            heavyWork.withLock {
            files.forEach { entry ->
                if (!SafeFilename.isSafe(entry.filename)) {
                    Log.w("DaedalusSync", "Skipping suspicious filename: ${entry.filename}")
                    return@forEach
                }
                val existing = repo.get(entry.filename)
                val localFile = existing?.localPath?.let { java.io.File(it) }
                val isComplete = existing != null && existing.durationMillis > 0L && localFile?.exists() == true && localFile.length() > 0L

                Log.i("DaedalusSync", "file=${entry.filename} isComplete=$isComplete duration=${existing?.durationMillis} deviceSize=${entry.sizeBytes}")
                if (isComplete) return@forEach
                _syncProgress.value = "Downloading ${entry.filename} via BLE…"
                AnalysisForegroundService.start(getApplication(), entry.filename, "Downloading via BLE…")
                val file = bleManager.downloadFile(entry.filename) { bytes ->
                    val statusText = "Downloading ${entry.filename} (${bytes / 1024} KB)…"
                    _syncProgress.value = statusText
                    AnalysisForegroundService.start(getApplication(), entry.filename, statusText)
                }
                if (file != null) {
                    val duration = AudioUtils.getDurationMillis(file.absolutePath)
                    saveSyncedRecording(
                        filename = entry.filename,
                        existing = existing,
                        localPath = file.absolutePath,
                        sizeBytes = file.length(),
                        durationMillis = duration,
                        deviceSerial = deviceSerial
                    )
                    newFilenames.add(entry.filename)
                    synced++
                } else {
                    Log.w("DaedalusSync", "Failed to download ${entry.filename} via BLE — will retry next sync")
                    failed++
                }
            }
            }

            _syncProgress.value = when {
                failed > 0 && synced > 0 -> "Synced $synced file(s), $failed failed"
                failed > 0 -> "$failed file(s) failed to download"
                synced > 0 -> "Synced $synced file(s)"
                else -> "All files already synced"
            }
            if (failed > 0) {
                _aiError.value = "$failed file(s) could not be downloaded from the device and will be retried on the next sync."
            }
            delay(1000)
            _syncProgress.value = null
            // Deliberately not part of syncJob: analysis of a long recording runs for many
            // minutes, and cancelling the sync that happened to kick it off must not throw
            // that work away. heavyWork still keeps it from overlapping a transfer, and
            // cancelSync() can still stop it via autoAnalyzeJob.
            if (autoAnalyzeJob?.isActive != true) {
                autoAnalyzeJob = viewModelScope.launch { autoAnalyzePending() }
            }
            } catch (e: CancellationException) {
                _syncProgress.value = null
                throw e
            } finally {
                AnalysisForegroundService.stop(getApplication())
            }
        }
    }

    /**
     * Builds and persists a Recording row for a file that just landed locally via any sync
     * path (BLE, USB-OTG, or SAF import), preserving whatever analysis already existed.
     * `deviceSerial` is passed explicitly by each call site so provenance is stated, not
     * inferred: BLE passes the connected unit's serial, USB-OTG and SAF pass null. A null
     * here never clobbers a serial an earlier BLE sync already recorded — it only fills in
     * when nothing was known before.
     */
    private suspend fun saveSyncedRecording(
        filename: String,
        existing: Recording?,
        localPath: String,
        sizeBytes: Long,
        durationMillis: Long,
        deviceSerial: String?
    ) {
        val calculatedCreatedAt = DateUtils.parseEpochMillisFromFilename(filename)
        val recording = existing ?: Recording(filename = filename, createdAt = calculatedCreatedAt)
        repo.save(recording.copy(
            localPath = localPath,
            sizeBytes = sizeBytes,
            durationMillis = durationMillis,
            createdAt = calculatedCreatedAt,
            deviceSerial = deviceSerial ?: existing?.deviceSerial,
            // This function only ever runs when fresh audio just landed, so a flag describing
            // the old copy no longer applies — otherwise a clean re-sync after pruning a bad
            // local file would leave the recording permanently skipped by auto-analysis.
            analysisFailed = false
        ))
    }

    // Single source of truth for "does this recording still need auto-analysis" — shared by
    // autoAnalyzePending()'s initial snapshot below and doAnalyze()'s in-lock re-check, so two
    // racing snapshots (fullAutoSync and syncFiles each call autoAnalyzePending() separately)
    // agree on the same rule instead of drifting apart.
    private fun Recording.needsAutoAnalysis(): Boolean = summary.isBlank() && !analysisFailed

    @VisibleForTesting
    internal suspend fun autoAnalyzePending() {
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        val autoProcess = prefs.getBoolean("auto_process", false)

        if (!autoProcess) return

        // Fetch current list from repo
        val recordings = repo.allRecordings.first()
        for (recording in recordings) {
            // analysisFailed skips the ones that already ran and produced nothing usable. Without
            // it, audio that can never yield a transcript is re-attempted on every sync — and the
            // costly failures are the ones that fail *after* a full Whisper pass, not the cheap
            // ones. A re-fetch clears the flag, and manual analysis never consults it.
            if (recording.needsAutoAnalysis() && recording.localPath.isNotBlank()) {
                val file = File(recording.localPath)
                if (file.exists()) {
                    _syncProgress.value = "Auto-analyzing ${recording.filename}…"
                    // Only delay after work that actually ran — doAnalyze() returns false when
                    // its own in-lock re-check found the recording already handled, and there's
                    // nothing to pace a delay against in that case.
                    if (doAnalyze(recording.filename, autoTriggered = true)) delay(500)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun fullAutoSync() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val localDir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
            
            Log.i("DaedalusSync", "Starting Auto-Sync...")
            _syncProgress.value = "Searching for USB..."
            _aiError.value = null

            withContext(ioDispatcher) {
                val storageManager = context.getSystemService(android.os.storage.StorageManager::class.java)
                val volumes = storageManager.storageVolumes
                
                Log.d("DaedalusSync", "Found ${volumes.size} storage volumes")
                var foundAny = false
                val commonFolders = listOf("RECORD", "Record", "RECORDER", "VOICE", "Voice")

                volumes.forEach { volume ->
                    Log.d("DaedalusSync", "Volume: ${volume.getDescription(context)} (Primary: ${volume.isPrimary}, Emulated: ${volume.isEmulated})")
                    if (volume.isPrimary || volume.isEmulated) return@forEach
                    
                    val mountPath = volume.directory
                    if (mountPath == null) {
                        Log.w("DaedalusSync", "Volume ${volume.getDescription(context)} has no directory path")
                        return@forEach
                    }

                    Log.i("DaedalusSync", "Scanning drive: $mountPath")

                    val recordDir = commonFolders.map { File(mountPath, it) }.find { it.exists() && it.isDirectory }
                    
                    if (recordDir != null) {
                        foundAny = true
                        Log.i("DaedalusSync", "Found recorder folder at: ${recordDir.absolutePath}")
                        val files = recordDir.listFiles()?.filter { it.name.endsWith(".mp3", ignoreCase = true) } ?: emptyList()
                        
                        Log.d("DaedalusSync", "Found ${files.size} MP3 files in recorder")

                        files.forEach { file ->
                            val destFile = File(localDir, file.name)
                            if (destFile.exists() && destFile.length() == file.length()) {
                                Log.d("DaedalusSync", "Skipping existing file: ${file.name}")
                                return@forEach
                            }

                            Log.i("DaedalusSync", "Syncing: ${file.name} (${file.length()} bytes)")
                            _syncProgress.value = "Syncing ${file.name}..."
                            
                            try {
                                file.inputStream().use { input ->
                                    FileOutputStream(destFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                val duration = AudioUtils.getDurationMillis(destFile.absolutePath)
                                saveSyncedRecording(
                                    filename = file.name,
                                    existing = repo.get(file.name),
                                    localPath = destFile.absolutePath,
                                    sizeBytes = destFile.length(),
                                    durationMillis = duration,
                                    // Mounted storage exposes no device identity — no serial recoverable here.
                                    deviceSerial = null
                                )
                            } catch (e: Exception) {
                                Log.e("DaedalusSync", "Error copying ${file.name}", e)
                                _aiError.value = "Failed to copy ${file.name}: ${e.message}"
                            }
                        }
                    }
                }
                
                if (!foundAny) {
                    Log.e("DaedalusSync", "No compatible recorder folder found on any external volume")
                    _aiError.value = "Recorder not found. Ensure USB OTG is connected and has a 'RECORD' folder."
                } else {
                    Log.i("DaedalusSync", "Auto-Sync complete")
                }
            }
            autoAnalyzePending()
            _syncProgress.value = null
            _currentNote.value?.let { loadNote(it.filename) }
        }
    }

    fun syncFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val localDir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }

            withContext(ioDispatcher) {
                uris.forEach { uri ->
                    val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@forEach
                    val name = docFile.name ?: "REC_${System.currentTimeMillis()}.mp3"
                    val destFile = File(localDir, name)

                    if (destFile.exists() && destFile.length() == docFile.length()) return@forEach

                    _syncProgress.value = "Syncing $name..."

                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        val duration = AudioUtils.getDurationMillis(destFile.absolutePath)
                        saveSyncedRecording(
                            filename = name,
                            existing = repo.get(name),
                            localPath = destFile.absolutePath,
                            sizeBytes = destFile.length(),
                            durationMillis = duration,
                            // Arbitrary source picked via SAF — no device to attribute.
                            deviceSerial = null
                        )
                    } catch (e: Exception) {
                        _aiError.value = "Failed to sync $name: ${e.message}"
                    }
                }
            }
            autoAnalyzePending()
            _syncProgress.value = null
            _currentNote.value?.let { loadNote(it.filename) }
        }
    }

    fun loadNote(filename: String) {
        viewModelScope.launch {
            _currentScanResult.value = null
            val note = repo.get(filename)
            _currentNote.value = note
            _currentScanResult.value = withContext(ioDispatcher) {
                val file = note?.localPath?.let { File(it) }?.takeIf { it.exists() }
                file?.let { Mp3FrameScan.scan(it) }
            }
        }
    }

    /** Puts the pre-download copy back after a transfer that deleted it and then failed. */
    private suspend fun restoreBackup(backup: File?, localPath: String) {
        val bak = backup ?: return
        withContext(ioDispatcher) {
            if (!bak.exists()) {
                Log.e("DaedalusSync", "Cannot restore $localPath: backup ${bak.absolutePath} " +
                    "is gone")
                return@withContext
            }
            // copyTo(overwrite = true) deletes localPath first, then streams from bak. If the
            // copy throws partway (full disk, permission revoked, media unmounted), localPath is
            // now empty or partial and bak is the ONLY remaining good copy — it must NOT be
            // deleted in that case, or the user loses the recording entirely.
            runCatching { bak.copyTo(File(localPath), overwrite = true) }
                .onSuccess { bak.delete() }
                .onFailure {
                    Log.e("DaedalusSync", "Could not restore $localPath from backup; keeping " +
                        "${bak.absolutePath} for manual recovery", it)
                }
        }
    }

    /** Returns all child parts for a recording that was split, empty if it wasn't split. */
    suspend fun getPartsOf(filename: String): List<Recording> = repo.getPartsOf(filename)

    /**
     * Debug ADB support for com.daedalus.notes.FORMAT_PARAGRAPHS: runs [TranscriptFormatter] over
     * the stored transcript for [filename] and returns the formatted result, or null if the
     * recording or its transcript doesn't exist.
     */
    suspend fun formatParagraphsPreview(filename: String): String? {
        val transcript = repo.get(filename)?.transcript?.takeIf { it.isNotBlank() } ?: return null
        return TranscriptFormatter.formatParagraphs(transcript)
    }

    /**
     * Debug ADB support for com.daedalus.notes.SEARCH_FTS: runs [query] through the same
     * search path the library screen's search bar uses (RecordingDao.searchFtsFlow — a Room
     * FTS4 index, see #101) and returns the matching filenames.
     */
    suspend fun searchPreview(query: String): List<String> = repo.search(query).first().map { it.filename }

    fun analyze(filename: String) {
        viewModelScope.launch { doAnalyze(filename) }
    }

    /**
     * Filenames with a re-download currently in flight. Guards against two concurrent
     * re-downloads of the same file: even with [heavyWork] now covering the whole critical
     * section (see below), a second call would still read a stale `recording` snapshot from
     * before the first call's `repo.save` — a fixed-name `.bak` collision is a symptom of the
     * same underlying problem, not a separate one. Rejecting the second attempt with a visible
     * error is preferred over uniquifying the backup name, which would let both proceed and
     * leave that stale-overwrite race in place. Only ever touched from viewModelScope.launch,
     * which runs on Dispatchers.Main — no separate lock needed for the set itself.
     *
     * The entry is removed as soon as the heavyWork-guarded critical section below ends (backup
     * creation through the #119 length guard and the DB write) rather than after the trailing
     * doAnalyze()/loadNote() calls. The stale-snapshot and `.bak`-collision problems this guards
     * against are both fully resolved once that section completes, so holding the entry any
     * longer only widens the window where the UI looks idle (_isProcessing already false) while
     * re-fetch is still rejected.
     */
    private val inFlightRedownloads = mutableSetOf<String>()

    /**
     * Pulls a fresh copy of the audio from the FW920 and re-runs analysis from scratch.
     * Ordinary sync skips any file that already exists locally, so a truncated or corrupt
     * download can never be repaired by syncing again — this is the way out of that.
     */
    fun redownloadAndAnalyze(requestedFilename: String, bleManager: BleManager) {
        viewModelScope.launch {
            // A part has no file of its own on the FW920 — re-fetch the parent it came from,
            // which is what the user means from a part's screen anyway.
            val requested = repo.get(requestedFilename)
            val filename = requested?.parentFilename ?: requestedFilename
            val recording = repo.get(filename) ?: run {
                Log.w("DaedalusSync", "Re-download: no DB row for '$filename'")
                _aiError.value = "Recording not found."
                return@launch
            }
            if (recording.isLocal) {
                Log.w("DaedalusSync", "Re-download: '$filename' is a phone recording")
                _aiError.value = "This was recorded on the phone — there is no device copy to fetch."
                return@launch
            }
            if (!isBleConnected(bleManager)) {
                Log.w("DaedalusSync", "Re-download: BLE not connected " +
                    "(state=${bleManager.bleState.value.connectionState})")
                _aiError.value = "Device not connected. Connect the FW920 to re-download."
                return@launch
            }

            // Refresh device file list over BLE before checking existence
            try {
                bleManager.listFiles()
            } catch (e: Exception) {
                Log.w("DaedalusSync", "Re-download: failed to refresh device file list", e)
            }

            val cleanName = if (filename.endsWith(".mp3")) filename.removeSuffix(".mp3") else filename
            val deviceFiles = bleManager.bleState.value.files
            if (deviceFiles.isNotEmpty() && deviceFiles.none { it.filename.equals(cleanName, ignoreCase = true) }) {
                Log.w("DaedalusSync", "Re-download: '$cleanName' no longer on device")
                _aiError.value = "Recording no longer exists on device."
                return@launch
            }

            if (!inFlightRedownloads.add(filename)) {
                Log.w("DaedalusSync", "Re-download: '$filename' already has a re-download in flight")
                _aiError.value = "Already re-downloading this recording — please wait for it to finish."
                return@launch
            }
            _isProcessing.value = true
            _aiError.value = null
            // Started only once the in-flight check above has accepted this call — a rejected
            // re-download (another one already running for this filename) should leave whatever
            // notification that other one is showing alone, not stomp it with a fresh "starting"
            // notification for a request that's about to be refused.
            AnalysisForegroundService.start(getApplication(), filename, "Re-downloading audio...")
            // downloadFile() throws on a full disk or a revoked permission, and by then it has
            // already deleted the original — an escaping exception would crash the app and
            // strand the only copy in an orphaned .bak, so treat a throw like a failed transfer.
            //
            // heavyWork now covers the *entire* critical section below — backup creation,
            // transfer, the #119 length guard, restore/delete, and deletePartsOf/save —
            // not just the transfer. Outside that scope, syncAllBleFiles could previously
            // start downloadFile() on this same filename while restoreBackup() was still
            // copying into the same path, or save a DB row with a length read mid-rewrite.
            // doAnalyze() below independently re-acquires heavyWork and Mutex is not
            // reentrant, so it (and loadNote) must stay outside this block or every
            // re-download deadlocks.
            //
            // Cost: heavyWork is a single global mutex, so this serializes re-download
            // against sync for every recording, not just the contended one, for the
            // duration of one transfer + DB write. Accepted here — re-download is
            // user-initiated and rare, and syncAllBleFiles already serializes its entire
            // file loop under this same lock, so this widens what the lock covers without
            // changing the order of magnitude of contention.
            try {
                _syncProgress.value = "Waiting for current processing to finish…"
                heavyWork.withLock {
                    // downloadFile() deletes the existing local copy before it starts
                    // streaming, so a failed transfer would otherwise destroy the only copy
                    // — and for a split recording leave the parent and every part pointing
                    // at a file that is gone. Keep a copy to put back if the transfer
                    // doesn't complete. Read live, under the lock, so it reflects whatever
                    // the most recent sync or re-download actually left at this path.
                    val current = File(recording.localPath).takeIf { it.exists() }
                    val backup = current?.let { src ->
                        withContext(ioDispatcher) {
                            runCatching { src.copyTo(File(src.parentFile, src.name + ".bak"), overwrite = true) }
                                .getOrNull()
                        }
                    }
                    // Captured now, not re-read later: File.length() on a since-vanished
                    // backup silently returns 0, which would make a truncated download look
                    // longer than "no backup" and sail straight through the guard below
                    // (#119 could reproduce with the guard in place). Comparing against this
                    // captured Long instead closes that hole.
                    val backupLength = backup?.length()

                    _syncProgress.value = "Re-downloading $filename…"
                    AnalysisForegroundService.start(getApplication(), filename, "Re-downloading $filename…")
                    Log.i("DaedalusSync", "Re-downloading $filename on request")
                    // Below: several early `return@launch`es sit inside this withLock. withLock
                    // is an inline function built on a plain try/finally around the lambda, so a
                    // non-local return out of the lambda still runs that finally and releases
                    // the mutex — it does not leak the lock the way it would if withLock were a
                    // suspend function that scheduled unlock separately.
                    val downloaded = try {
                        bleManager.downloadFile(filename) { bytes ->
                            val statusText = "Re-downloading $filename (${bytes / 1024} KB)…"
                            _syncProgress.value = statusText
                            AnalysisForegroundService.start(getApplication(), filename, statusText)
                        }
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { restoreBackup(backup, recording.localPath) }
                        throw e
                    } catch (e: Exception) {
                        Log.e("DaedalusSync", "Re-download of $filename threw; restoring previous copy", e)
                        restoreBackup(backup, recording.localPath)
                        _aiError.value = e.message ?: "Re-download failed."
                        return@launch
                    }

                    if (downloaded == null) {
                        restoreBackup(backup, recording.localPath)
                        Log.w("DaedalusSync", "Re-download of $filename failed; restored previous copy")
                        _aiError.value = "Re-download failed. Keep the FW920 connected and try again."
                        return@launch
                    }

                    // A non-null return only means the FW920 sent an EOF ack — it says
                    // nothing about how many bytes actually arrived (#119: the same file
                    // transferred as 337148 bytes on one attempt and 217412 on another, both
                    // ack'd clean). The backup made above is the only completeness oracle
                    // available today, so use it: a strictly shorter replacement is rejected
                    // outright. Equal is the expected good case, and longer is legitimate too
                    // — the previous local copy may itself have been the truncated one,
                    // which is exactly the case this whole feature exists to fix — so only
                    // "shorter" is treated as suspect, with no tolerance/threshold to tune.
                    if (backupLength != null && downloaded.length() < backupLength) {
                        Log.w("DaedalusSync", "Re-download of $filename came back shorter than the " +
                            "backup (${downloaded.length()} < $backupLength bytes); restoring previous copy")
                        // Not cancellable: a cancellation landing between the length check
                        // and the restore completing must not leave localPath holding the
                        // truncated download with no restore having run.
                        withContext(NonCancellable) { restoreBackup(backup, recording.localPath) }
                        _aiError.value = "Re-download came back shorter than the copy it replaced " +
                            "(${downloaded.length()} vs $backupLength bytes). Keep the FW920 " +
                            "connected and try again."
                        return@launch
                    }
                    withContext(ioDispatcher + NonCancellable) { backup?.delete() }

                    // Fresh audio: drop the parts and analysis derived from the old copy, and
                    // commit the new file's metadata, as one non-cancellable unit. The good new
                    // file is already at localPath at this point; without NonCancellable, a
                    // cancellation landing after deletePartsOf/the duration read but before
                    // repo.save would leave the DB row's sizeBytes/durationMillis/transcript
                    // stale against that new file — not data loss, but a note that shows an old
                    // transcript over new audio, with the backup already deleted above.
                    withContext(NonCancellable) {
                        repo.deletePartsOf(filename)
                        repo.save(recording.copy(
                            localPath = downloaded.absolutePath,
                            sizeBytes = downloaded.length(),
                            durationMillis = withContext(ioDispatcher) {
                                AudioUtils.getDurationMillis(downloaded.absolutePath)
                            },
                            transcript = "", summary = "", mindMap = "",
                            title = "", shortSummary = "", topics = emptyList(), embedding = null,
                            // The flag described the old copy of the audio. This is a different
                            // file, and a re-fetch is exactly how a recording written off as
                            // unreadable gets rescued.
                            analysisFailed = false
                        ))
                    }
                }
            } finally {
                _isProcessing.value = false
                _syncProgress.value = null
                AnalysisForegroundService.stop(getApplication())
                // Released here, at the end of the heavyWork-guarded critical section, rather
                // than after doAnalyze()/loadNote() below — see the doc comment on
                // [inFlightRedownloads] for why the guard's job is done by this point. Reached
                // on every path out of the try above, including the early `return@launch`s
                // inside heavyWork.withLock, since a non-local return still runs enclosing
                // finally blocks.
                inFlightRedownloads.remove(filename)
            }

            // Outside heavyWork: doAnalyze() re-acquires it, and Mutex is not reentrant.
            doAnalyze(filename)
            loadNote(filename)
        }
    }

    /**
     * Both analysis paths — split and whole-file — end here when nothing readable came out.
     * Only a content-level failure is remembered: a missing model is an environment problem the
     * next sync may well find fixed, and flagging it would stop auto-analysis from ever retrying
     * once the model is installed. See [Recording.analysisFailed].
     */
    private suspend fun reportNothingReadable(filename: String) {
        val modelReady = isWhisperReady(getApplication())
        _aiError.value = if (modelReady) {
            "No speech or readable content detected in this recording."
        } else {
            "Transcription model not found. Please download it in Settings."
        }
        if (modelReady) repo.updateAnalysisFailed(filename, true)
    }

    /**
     * Waits for any in-flight transfer or analysis; see [heavyWork]. Returns whether analysis
     * work actually ran — false only when an auto-triggered call's in-lock re-check finds the
     * recording already handled and skips it.
     *
     * The single repo.get() below runs before doAnalyzeExclusive flips _isProcessing/starts the
     * foreground service — for every caller, not just auto-triggered ones — so a skip never
     * flashes a real "processing" notification, and the fetched note is handed to
     * doAnalyzeExclusive rather than fetched a second time there.
     */
    private suspend fun doAnalyze(filename: String, autoTriggered: Boolean = false): Boolean = heavyWork.withLock {
        val note = repo.get(filename)
        // A manual analyze can beat a queued auto-analyze call to the front of heavyWork, and
        // the separate autoAnalyzePending() calls from fullAutoSync and syncFiles can each
        // snapshot the same recording too — either way, only auto-triggered work is skipped;
        // a deliberate re-analysis must still run.
        if (autoTriggered && note != null && !note.needsAutoAnalysis()) {
            Log.i("DaedalusAI", "Skipping auto-analyze of $filename; already handled")
            return@withLock false
        }
        doAnalyzeExclusive(filename, note)
        true
    }

    private suspend fun doAnalyzeExclusive(filename: String, note: Recording?) {
            _isProcessing.value = true
            _aiError.value = null
            AnalysisForegroundService.start(getApplication(), filename, "Processing audio...")
            try {
                var note = note ?: run {
                    _aiError.value = "Recording not synced. Download it first."
                    return
                }

                val localFile = note.localPath.let { java.io.File(it) }.takeIf { it.exists() } ?: run {
                    _aiError.value = "Audio file missing — sync the recording first."
                    return
                }

                // Determine duration, healing the DB value if needed.
                val durationMs = note.durationMillis.takeIf { it > 0 }
                    ?: withContext(ioDispatcher) {
                        AudioUtils.getDurationMillis(localFile.absolutePath)
                    }.also { d ->
                        // Rebind `note` so the later copy(...) saves below don't write the stale 0 back.
                        if (d > 0) {
                            note = note.copy(durationMillis = d)
                            repo.save(note)
                        }
                    }

                // --- Long-recording split: > 15 min → create parts ---
                if (durationMs > PART_DURATION_MS && note.parentFilename == null) {
                    val numParts = ((durationMs + PART_DURATION_MS - 1) / PART_DURATION_MS).toInt()
                    Log.i("DaedalusAI", "Splitting ${filename} into $numParts parts (${durationMs}ms)")

                    var created = 0
                    // Distinguishes "part 1 threw" from "part 1 transcribed to nothing readable"
                    // below — created==0 covers both, but only the latter is a genuine failure of
                    // the audio. A transient exception (native OOM, IO error) must stay retryable,
                    // matching the flag-free generic catch at the bottom of doAnalyzeExclusive.
                    var abortedByError = false
                    val fullTranscript = StringBuilder()
                    val createdParts = mutableListOf<Recording>()
                    for (i in 1..numParts) {
                        val startMs = (i - 1) * PART_DURATION_MS
                        val endMs = minOf(i * PART_DURATION_MS, durationMs)
                        // Keep the parent's extension (BLE keys have none, imports keep .mp3,
                        // phone recordings are .m4a) so a part key never claims a wrong container.
                        val ext = filename.substringAfterLast('.', "")
                        val partFilename = filename.substringBeforeLast('.', filename) +
                            "_p$i" + if (ext.isEmpty()) "" else ".$ext"
                        val partDuration = endMs - startMs

                        _syncProgress.value = "Transcribing part $i of $numParts…"
                        AnalysisForegroundService.start(getApplication(), filename, "Transcribing part $i of $numParts…")
                        Log.i("DaedalusAI", "Transcribing part $i: ${startMs}ms–${endMs}ms")
                        val transcript = try {
                            transcriber.transcribeRange(localFile, startMs, endMs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Stop here rather than abandoning the run: the parent is still
                            // written below from the parts that did succeed, so a later sync
                            // won't see a blank summary and re-split the whole recording.
                            Log.e("DaedalusAI", "Part $i failed; keeping the $created part(s) done so far", e)
                            _aiError.value = e.message ?: "Analysis failed part-way through"
                            abortedByError = true
                            break
                        }

                        // The container's duration metadata can overrun the decodable audio
                        // (observed on FW920 files), leaving trailing ranges with no samples.
                        // A part with nothing readable in it is a blank card, so don't make one.
                        if (!isTranscriptReadable(transcript)) {
                            Log.w("DaedalusAI", "Part $i produced no readable transcript — skipping")
                            continue
                        }

                        val partRecording = Recording(
                            filename = partFilename,
                            localPath = localFile.absolutePath,
                            sizeBytes = note.sizeBytes,
                            transcript = transcript,
                            durationMillis = partDuration,
                            createdAt = note.createdAt + (i - 1),  // slight offset to preserve order
                            isLocal = note.isLocal,
                            deviceSerial = note.deviceSerial,
                            parentFilename = filename,
                            partIndex = i
                        )
                        createdParts.add(partRecording)
                        created++
                        if (fullTranscript.isNotEmpty()) fullTranscript.append("\n\n")
                        fullTranscript.append(transcript)
                    }

                    // Mark the parent as split so it shows the part count in the UI. Use the
                    // parts actually created — trailing ranges can be skipped above.
                    if (created > 0) {
                        repo.deletePartsOf(filename)
                        createdParts.forEach { part ->
                            repo.save(part)
                            try {
                                analyzeTranscript(
                                    getApplication(), llm, embedder, repo,
                                    part.filename, part.transcript
                                ) {
                                    _syncProgress.value = it
                                    AnalysisForegroundService.start(getApplication(), part.filename, it)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("DaedalusAI", "Gemma analysis of part ${part.partIndex} failed; transcript kept", e)
                            }
                        }

                        // Keep the joined transcript and a summary stitched from the parts on the
                        // parent: NoteDetail, export, backup, search, the knowledge graph and
                        // Ask all read the parent row and would otherwise see it as empty. A
                        // blank summary would also make autoAnalyzePending() re-split forever.
                        val savedParts = repo.getPartsOf(filename)
                        val summary = savedParts.joinToString("\n\n") { p ->
                            "## Part ${p.partIndex}: ${p.title.ifBlank { "Untitled" }}\n${p.summary}"
                        }
                        // Topics come from the parts too. Without them the knowledge graph
                        // (which iterates Recording.topics) drops split recordings entirely,
                        // and Ask backfills the parent's embedding from shortSummary + topics —
                        // i.e. from "Split into N parts…", so every long recording would embed
                        // to the same boilerplate vector and match every query equally.
                        val mergedTopics = savedParts.flatMap { it.topics }.distinct()
                        // The count lives only in the summary, which is rewritten every run.
                        // Putting it in the kept-if-present title made the two drift apart
                        // ("Long Recording (2 parts)" over "Split into 1 parts").
                        repo.save(note.copy(
                            transcript = fullTranscript.toString(),
                            summary = summary,
                            topics = mergedTopics,
                            // A previously stored placeholder (including the old
                            // "Long Recording (2 parts)" form) is replaced, not preserved;
                            // only a real Gemma-derived title survives a re-analysis.
                            title = note.title
                                .takeIf { it.isNotBlank() && !SPLIT_PLACEHOLDER_TITLE.matches(it) }
                                ?: "Long Recording",
                            shortSummary = if (abortedByError) {
                                "Split into $created of $numParts parts — analysis was interrupted. Re-analyze to finish."
                            } else if (created == 1) {
                                "Split into 1 part. Tap to expand."
                            } else {
                                "Split into $created parts of ~15 min each. Tap to expand."
                            },
                            analysisFailed = false
                        ))

                        // Embed the parts' own summaries, not the "Split into N parts" boilerplate.
                        if (embedder.isReady) {
                            embedder.ensureLoaded()
                            val embText = savedParts.joinToString(" ") {
                                "${it.shortSummary} ${it.topics.joinToString(" ")}"
                            }
                            embedder.embed(embText)?.let { repo.updateEmbedding(filename, it) }
                        }
                    } else if (!abortedByError) {
                        // Genuinely nothing readable in the audio — the exception path above
                        // already surfaced its own error and must not be overwritten or flagged.
                        reportNothingReadable(filename)
                    }
                    _currentNote.value = repo.get(filename)
                    return
                }

                // --- Normal (short) recording: transcribe + analyze as one ---
                // A parent that is no longer over the threshold (repaired container, healed
                // duration) must shed the parts an earlier split left behind, or the list keeps
                // offering "Show parts" over transcripts from the old audio.
                if (note.parentFilename == null) repo.deletePartsOf(filename)

                _syncProgress.value = "Transcribing audio…"
                AnalysisForegroundService.start(getApplication(), filename, "Transcribing audio…")
                Log.i("DaedalusAI", "Transcribing ${localFile.name}")
                // Re-analyzing a single part must stay within that part's window — it shares the
                // parent's audio file, so a plain transcribe() would pull in the whole recording.
                val transcript = if (note.parentFilename != null && note.partIndex > 0) {
                    val startMs = (note.partIndex - 1) * PART_DURATION_MS
                    transcriber.transcribeRange(localFile, startMs, startMs + durationMs)
                } else {
                    transcriber.transcribe(localFile)
                }
                repo.save(note.copy(transcript = transcript))

                if (!isTranscriptReadable(transcript)) {
                    reportNothingReadable(filename)
                    return
                }

                // Step 2: Summarize + mind map with Gemma, embed for library Q&A (shared with
                // ConversationViewModel.endSession, which already has a transcript in hand).
                analyzeTranscript(getApplication(), llm, embedder, repo, filename, transcript) {
                    _syncProgress.value = it
                    AnalysisForegroundService.start(getApplication(), filename, it)
                }
                // The split path above clears the flag inside its own parent save rather than
                // paying for a second write here.
                repo.updateAnalysisFailed(filename, false)

                _currentNote.value = repo.get(filename)
            } catch (e: CancellationException) {
                // Structured concurrency: cancellation is not a failure and must propagate,
                // or the coroutine machinery is left believing this job is still alive.
                Log.i("DaedalusAI", "Analysis of $filename cancelled")
                throw e
            } catch (e: Exception) {
                Log.e("DaedalusAI", "Analysis failed", e)
                _aiError.value = e.message ?: "Unknown AI error"
            } finally {
                _isProcessing.value = false
                _syncProgress.value = null
                AnalysisForegroundService.stop(getApplication())
            }
    }

    fun clearExportIntent() { _exportIntent.value = null }

    private fun isBleConnected(bleManager: BleManager): Boolean =
        bleManager.bleState.value.connectionState == ConnectionState.CONNECTED

    fun updateTitleAndSummary(filename: String, title: String, shortSummary: String) {
        viewModelScope.launch { repo.updateTitleAndSummary(filename, title, shortSummary) }
    }

    fun deleteRecording(filename: String, bleManager: BleManager) {
        viewModelScope.launch {
            val recording = repo.get(filename) ?: return@launch

            // A row must skip local file delete and hardware wipe iff some other row shares this
            // localPath and is NOT one of this row's own parts (which are cascade-deleted below).
            val otherSharesPath = repo.countOtherSharingPath(recording.localPath, recording.filename) > 0
            if (otherSharesPath) {
                repo.delete(recording)
                return@launch
            }

            // Local-only recordings aren't on the FW920 — delete without requiring a device.
            if (recording.isLocal) {
                deleteFileSafely(recording.localPath)
                repo.delete(recording)
                repo.deletePartsOf(filename)
                _syncProgress.value = "Deleted successfully"
                delay(1500)
                _syncProgress.value = null
                return@launch
            }

            if (!isBleConnected(bleManager)) {
                // Device not connected — delete local cache and queue delete for later
                deleteFileSafely(recording.localPath)
                repo.markPendingDelete(filename)
                repo.deletePartsOf(filename)
                _syncProgress.value = "Queued deletion"
                delay(1500)
                _syncProgress.value = null
                return@launch
            }

            // 1. Try to delete from physical device via BLE
            _syncProgress.value = "Deleting from device…"
            val bleSuccess = bleManager.deleteFile(filename)
            Log.i("RecordingViewModel", "BLE delete result: $bleSuccess")
            
            if (bleSuccess) {
                // 2. Remove local file
                deleteFileSafely(recording.localPath)
                // 3. Remove from database, child parts included
                repo.delete(recording)
                repo.deletePartsOf(filename)
                _syncProgress.value = "Deleted successfully"
            } else {
                _aiError.value = "Hardware delete failed. File still on FW920."
                _syncProgress.value = "Delete failed"
            }
            delay(1500)
            _syncProgress.value = null
        }
    }

    fun deleteMultipleRecordings(filenames: List<String>, bleManager: BleManager) {
        viewModelScope.launch {
            val recordings = filenames.mapNotNull { repo.get(it) }
            _isProcessing.value = true
            var count = 0
            var failedCount = 0
            var queuedCount = 0
            val total = recordings.size

            val connected = isBleConnected(bleManager)

            for (recording in recordings) {
                count++
                _syncProgress.value = "Deleting $count of $total..."

                // Same reasoning as deleteRecording: avoid deleting files used by other rows.
                val otherSharesPath = repo.countOtherSharingPath(recording.localPath, recording.filename) > 0
                if (otherSharesPath) {
                    repo.delete(recording)
                    continue
                }

                // Child parts are cascade-deleted below, but only once the parent's own
                // deletion has actually gone through — a failed BLE wipe keeps the parent,
                // and its parts must survive with it.
                if (recording.isLocal) {
                    deleteFileSafely(recording.localPath)
                    repo.delete(recording)
                    repo.deletePartsOf(recording.filename)
                    continue
                }

                if (!connected) {
                    // Queue hardware deletion, remove local cache
                    deleteFileSafely(recording.localPath)
                    repo.markPendingDelete(recording.filename)
                    repo.deletePartsOf(recording.filename)
                    queuedCount++
                    continue
                }

                // 1. Hardware wipe
                val bleSuccess = bleManager.deleteFile(recording.filename)

                if (bleSuccess) {
                    // 2. Local cleanup
                    deleteFileSafely(recording.localPath)
                    repo.delete(recording)
                    repo.deletePartsOf(recording.filename)
                } else {
                    Log.w("RecordingViewModel", "Failed to wipe ${recording.filename} from hardware")
                    failedCount++
                }
            }
            
            if (failedCount > 0) {
                _syncProgress.value = "Done ($failedCount failed)"
                _aiError.value = "Some files could not be deleted from the FW920 hardware."
            } else if (queuedCount > 0) {
                _syncProgress.value = "Queued $queuedCount deletion(s)"
            } else {
                _syncProgress.value = "Deleted $total items"
            }
            delay(1500)
            _syncProgress.value = null
            _isProcessing.value = false
        }
    }

    fun exportMarkdown(filename: String) {
        viewModelScope.launch {
            // Export the whole recording, whether the user is looking at the parent or at one
            // part — a part on its own is half a meeting.
            val requested = repo.get(filename) ?: return@launch
            val recording = requested.parentFilename?.let { repo.get(it) } ?: requested
            val content = MarkdownExporter.export(recording, repo.getPartsOf(recording.filename))
            val context = getApplication<Application>()

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outFile = File(downloadsDir, "${File(recording.filename).nameWithoutExtension}.md")
            withContext(ioDispatcher) { outFile.writeText(content) }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", outFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _exportIntent.value = Intent.createChooser(shareIntent, "Export as Markdown")
        }
    }

    fun exportLibraryAnswer() {
        val answer = _libraryAnswer.value ?: return
        viewModelScope.launch {
            val content = MarkdownExporter.exportQa(_libraryQuestion.value, answer, _librarySources.value)
            val context = getApplication<Application>()

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outFile = File(downloadsDir, "ask-${System.currentTimeMillis()}.md")
            withContext(ioDispatcher) { outFile.writeText(content) }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", outFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _exportIntent.value = Intent.createChooser(shareIntent, "Export answer as Markdown")
        }
    }

    fun clearAskAnswer() {
        _askAnswer.value = null
        _libraryAnswer.value = null
        _librarySources.value = emptyList()
    }

    fun askNoteQuestion(filename: String, question: String) {
        viewModelScope.launch {
            _isAsking.value = true
            _askAnswer.value = null
            _aiError.value = null
            try {
                val note = repo.get(filename) ?: run {
                    _aiError.value = "Note not found."
                    return@launch
                }
                if (note.shortSummary.isBlank() && note.summary.isBlank()) {
                    _aiError.value = "Analyze this note first to enable Q&A."
                    return@launch
                }
                llm.ensureLoaded()
                val context = buildNoteQuestionPrompt(
                    note.title,
                    note.shortSummary.ifBlank { note.summary.take(400) }
                )
                _askAnswer.value = llm.generate(context, question)
            } catch (e: Exception) {
                Log.e("DaedalusAI", "askNoteQuestion failed", e)
                _aiError.value = e.message ?: "Q&A failed"
            } finally {
                _isAsking.value = false
            }
        }
    }

    fun askLibraryQuestion(question: String) {
        viewModelScope.launch {
            _isAsking.value = true
            _libraryQuestion.value = question
            _libraryAnswer.value = null
            _librarySources.value = emptyList()
            _aiError.value = null
            try {
                if (!embedder.isReady) {
                    _aiError.value = "Download the embedding model in Settings to use Ask Library."
                    return@launch
                }
                embedder.ensureLoaded()
                val queryEmbed = embedder.embed(question) ?: run {
                    _aiError.value = "Could not embed question."
                    return@launch
                }
                // Wait for the DB to emit rather than reading the potentially-empty initial StateFlow value
                val all = repo.allRecordings.first().filter { it.summary.isNotBlank() }

                Log.d("DaedalusAI", "askLibrary: ${all.size} analyzed notes, embedding backfill starting")
                // Backfill embeddings for any notes that don't have them yet, updating DB and memory together
                val withEmbeddings = mutableListOf<Recording>()
                for (r in all) {
                    val resolved = if (r.embedding != null) r
                    else {
                        val text = "${r.shortSummary} ${r.topics.joinToString(" ")}"
                        val emb = embedder.embed(text)
                        Log.d("DaedalusAI", "Backfill embed '${r.filename}': ${if (emb != null) "ok (${emb.size}d)" else "null"}")
                        if (emb != null) {
                            repo.updateEmbedding(r.filename, emb)
                            r.copy(embedding = emb)
                        } else r
                    }
                    withEmbeddings.add(resolved)
                }

                val sources = repo.semanticSearch(queryEmbed, withEmbeddings, topK = 5)
                if (sources.isEmpty()) {
                    _aiError.value = "No note embeddings found. Re-analyze your notes to enable library search."
                    return@launch
                }
                // Only the note bodies are counted against the budget, but the prompt also carries a
                // preamble, a title line per note and the guardrail, so keep a fraction back as headroom.
                val graphBudget = aiTextBudget(getApplication()) * 3 / 4
                val expandedSources = expandWithTopicSiblings(sources, withEmbeddings, graphBudget)
                _librarySources.value = expandedSources
                val context = buildLibraryQuestionPrompt(expandedSources)
                llm.ensureLoaded()
                _libraryAnswer.value = llm.generate(context, question)
            } catch (e: Exception) {
                Log.e("DaedalusAI", "askLibraryQuestion failed", e)
                _aiError.value = e.message ?: "Library Q&A failed"
            } finally {
                _isAsking.value = false
            }
        }
    }

    private val backupManager by lazy { BackupManager(getApplication(), db) }

    fun exportBackup(uri: Uri, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    backupManager.exportToUri(uri)
                }
                onSuccess()
            } catch (e: Exception) {
                Log.e("RecordingViewModel", "Failed to export backup", e)
                onError(e.message ?: "Unknown error")
            }
        }
    }

    fun importBackup(uri: Uri, onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val importedCount = withContext(ioDispatcher) {
                    backupManager.importFromUri(uri)
                }
                onSuccess(importedCount)
            } catch (e: Exception) {
                Log.e("RecordingViewModel", "Failed to import backup", e)
                onError(e.message ?: "Unknown error")
            }
        }
    }

    fun wipeLocalAnalysis(deleteLocalAudio: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    if (deleteLocalAudio) {
                        val recordings = repo.allRecordings.first()
                        recordings.forEach { r ->
                            deleteFileSafely(r.localPath)
                        }
                    }
                    // Split parts only exist as a product of analysis — wiping analysis
                    // removes them outright rather than leaving rows with no content.
                    repo.allRecordings.first().forEach { repo.deletePartsOf(it.filename) }
                    repo.wipeAllAnalysis()
                    if (deleteLocalAudio) {
                        val recordings = repo.allRecordings.first()
                        recordings.forEach { r ->
                            repo.save(r.copy(localPath = "", sizeBytes = 0L))
                        }
                    }
                }
                onSuccess()
            } catch (e: Exception) {
                Log.e("RecordingViewModel", "Failed to wipe analysis", e)
                onError(e.message ?: "Unknown error")
            }
        }
    }

    private fun deleteFileSafely(path: String) {
        if (path.isBlank()) return
        try {
            val f = File(path).canonicalFile
            val appFilesDir = getApplication<Application>().getExternalFilesDir(null)?.canonicalFile
            if (appFilesDir != null && f.path.startsWith(appFilesDir.path + File.separator)) {
                if (f.exists()) {
                    f.delete()
                }
            } else {
                Log.w("RecordingViewModel", "Skipped deleting out-of-sandbox file: $path")
            }
        } catch (e: Exception) {
            Log.e("RecordingViewModel", "Failed to safely delete file: $path", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        embedder.close()
    }
}
