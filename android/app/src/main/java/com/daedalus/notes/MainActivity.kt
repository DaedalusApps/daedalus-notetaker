package com.daedalus.notes

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.io.File
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.daedalus.notes.ble.ConnectionState
import com.daedalus.notes.data.backup.BackupPrefs
import com.daedalus.notes.data.backup.BackupWorker
import kotlinx.coroutines.launch
import com.daedalus.notes.ui.NavGraph
import com.daedalus.notes.ui.theme.DaedalusTheme
import com.daedalus.notes.viewmodel.ConversationViewModel
import com.daedalus.notes.viewmodel.DeviceViewModel
import com.daedalus.notes.viewmodel.RecordingViewModel
import com.daedalus.notes.viewmodel.TodoViewModel

class MainActivity : ComponentActivity() {

    private val deviceViewModel: DeviceViewModel by viewModels()
    private val recordingViewModel: RecordingViewModel by viewModels()
    private val todoViewModel: TodoViewModel by viewModels()
    private val conversationViewModel: ConversationViewModel by viewModels()

    private val adbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AdbActions.SYNC -> {
                    Log.i("DaedalusADB", "ADB BLE sync triggered")
                    recordingViewModel.syncAllBleFiles(deviceViewModel.bleManager)
                }
                AdbActions.PROBE -> {
                    Log.i("DaedalusADB", "BLE probe triggered")
                    lifecycleScope.launch {
                        deviceViewModel.bleManager.runProbe()
                    }
                }
                AdbActions.PROBE2 -> {
                    Log.i("DaedalusADB", "Service probe triggered")
                    lifecycleScope.launch {
                        deviceViewModel.bleManager.runServiceProbe()
                    }
                }
                AdbActions.PROBE_DELETE -> {
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Delete probe triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        lifecycleScope.launch {
                            deviceViewModel.bleManager.probeDeleteCmds(filename)
                        }
                    }
                }
                AdbActions.PROBE_UPLOAD -> {
                    Log.i("DaedalusADB", "Upload probe triggered")
                    lifecycleScope.launch {
                        deviceViewModel.bleManager.probeUploadCmds()
                    }
                }
                AdbActions.START_RECORDING -> {
                    Log.i("DaedalusADB", "Device start-recording triggered")
                    lifecycleScope.launch { deviceViewModel.bleManager.startDeviceRecording() }
                }
                AdbActions.STOP_RECORDING -> {
                    Log.i("DaedalusADB", "Device stop-recording triggered")
                    lifecycleScope.launch { deviceViewModel.bleManager.stopDeviceRecording() }
                }
                AdbActions.ANALYZE -> {
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Analyze triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        lifecycleScope.launch { recordingViewModel.analyze(filename) }
                    }
                }
                AdbActions.REDOWNLOAD -> {
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Re-download + analyze triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        lifecycleScope.launch {
                            recordingViewModel.redownloadAndAnalyze(filename, deviceViewModel.bleManager)
                        }
                    }
                }
                AdbActions.DELETE_FILE -> {
                    // Invokes the same hardware delete the app's delete path uses
                    // (RecordingViewModel.deleteRecording -> BleManager.deleteFile), which
                    // two-phase-deletes over BLE then re-lists to confirm the file is gone.
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Hardware delete triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        lifecycleScope.launch {
                            val ok = deviceViewModel.bleManager.deleteFile(filename)
                            Log.i("DaedalusADB", "Hardware delete result for '$filename': $ok")
                        }
                    }
                }
                AdbActions.ADD_CALENDAR -> {
                    val title = intent?.getStringExtra("title") ?: "Action Item"
                    val note = intent?.getStringExtra("note") ?: ""
                    Log.i("DaedalusADB", "Add to calendar triggered for '$title'")
                    com.daedalus.notes.util.CalendarIntegration.addToCalendar(this@MainActivity, title, note)
                }
                AdbActions.REPAIR_FILE -> {
                    // Quarantined — see AdbActions.QUARANTINED. AudioRepairEngine.repairMp3File
                    // currently truncates audio at the first gap with no backup, so this branch
                    // is deliberately unreachable: it has no IntentFilter registration and never
                    // will until that data-loss bug is fixed on its own branch.
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Repair file triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        val file = File(getExternalFilesDir(null), "Recordings/$filename")
                        val ok = com.daedalus.notes.data.model.AudioRepairEngine.repairMp3File(file)
                        Log.i("DaedalusADB", "Audio repair result for '$filename': $ok")
                    }
                }
                AdbActions.SET_SPEED -> {
                    val speed = intent?.getFloatExtra("speed", -1f) ?: -1f
                    Log.i("DaedalusADB", "Set speed triggered: $speed")
                    if (speed > 0f) {
                        recordingViewModel.setPlaybackSpeed(speed)
                        Log.i("DaedalusADB", "Playback speed set to $speed")
                    }
                }
                AdbActions.FORMAT_SPEAKER -> {
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Format speaker triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        lifecycleScope.launch {
                            val formatted = recordingViewModel.formatSpeakerPreview(filename)
                            Log.i("DaedalusADB", "Speaker format result for '$filename': ${formatted ?: "no transcript"}")
                        }
                    }
                }
                AdbActions.SEARCH_FTS -> {
                    val query = intent?.getStringExtra("query") ?: ""
                    Log.i("DaedalusADB", "Search triggered for '$query'")
                    if (query.isNotBlank()) {
                        lifecycleScope.launch {
                            val results = recordingViewModel.searchPreview(query)
                            Log.i("DaedalusADB", "Search result for '$query': ${results.size} match(es) -> $results")
                        }
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        if (BuildConfig.DEBUG) {
            // Built from AdbActions.REGISTERED — the single source of truth shared with the
            // `when` block above and AndroidManifest.xml's .AdbReceiver — so a handler can't
            // silently end up with no registration (see #99).
            val filter = IntentFilter().apply {
                AdbActions.REGISTERED.forEach { addAction(it) }
            }
            // ADB shell (uid 2000) broadcasts are not delivered to RECEIVER_NOT_EXPORTED
            // receivers on Android 14+; this receiver is debug-only and exists solely so
            // `adb shell am broadcast` can trigger it during development.
            ContextCompat.registerReceiver(this, adbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }

        // Auto-sync on first BLE connect and when hardware recording finishes.
        // lastState and lastIsRecording live outside repeatOnLifecycle.
        var lastState = ConnectionState.DISCONNECTED
        var lastIsRecording = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                deviceViewModel.state.collect { bleState ->
                    val isConnected = bleState.connectionState == ConnectionState.CONNECTED
                    val wasConnected = lastState == ConnectionState.CONNECTED

                    if (isConnected && !wasConnected) {
                        Log.i("DaedalusADB", "BLE connected — auto-syncing")
                        recordingViewModel.syncAllBleFiles(deviceViewModel.bleManager)
                    }

                    if (isConnected && lastIsRecording && !bleState.isRecording) {
                        Log.i("DaedalusADB", "Hardware recording finished — auto-syncing")
                        recordingViewModel.syncAllBleFiles(deviceViewModel.bleManager)
                    }

                    lastState = bleState.connectionState
                    lastIsRecording = bleState.isRecording
                }
            }
        }

        val prefs = getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        if (!prefs.getString(BackupPrefs.FOLDER_URI, null).isNullOrBlank()) {
            BackupWorker.schedule(this, prefs.getLong(BackupPrefs.INTERVAL_HOURS, BackupPrefs.DEFAULT_INTERVAL_HOURS))
        }

        setContent {
            DaedalusTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavGraph(
                        navController = navController,
                        deviceViewModel = deviceViewModel,
                        recordingViewModel = recordingViewModel,
                        todoViewModel = todoViewModel,
                        conversationViewModel = conversationViewModel
                    )
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(adbReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }
}
