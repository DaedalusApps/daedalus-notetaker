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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.daedalus.notes.ble.ConnectionState
import kotlinx.coroutines.launch
import com.daedalus.notes.ui.NavGraph
import com.daedalus.notes.ui.theme.DaedalusTheme
import com.daedalus.notes.viewmodel.DeviceViewModel
import com.daedalus.notes.viewmodel.RecordingViewModel

class MainActivity : ComponentActivity() {

    private val deviceViewModel: DeviceViewModel by viewModels()
    private val recordingViewModel: RecordingViewModel by viewModels()

    private val adbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.daedalus.notes.SYNC" -> {
                    Log.i("DaedalusADB", "ADB BLE sync triggered")
                    recordingViewModel.syncAllBleFiles(deviceViewModel.bleManager)
                }
                "com.daedalus.notes.PROBE" -> {
                    Log.i("DaedalusADB", "BLE probe triggered")
                    lifecycleScope.launch {
                        deviceViewModel.bleManager.runProbe()
                    }
                }
                "com.daedalus.notes.PROBE2" -> {
                    Log.i("DaedalusADB", "Service probe triggered")
                    lifecycleScope.launch {
                        deviceViewModel.bleManager.runServiceProbe()
                    }
                }
                "com.daedalus.notes.PROBE_DELETE" -> {
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Delete probe triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        lifecycleScope.launch {
                            deviceViewModel.bleManager.probeDeleteCmds(filename)
                        }
                    }
                }
                "com.daedalus.notes.PROBE_UPLOAD" -> {
                    Log.i("DaedalusADB", "Upload probe triggered")
                    lifecycleScope.launch {
                        deviceViewModel.bleManager.probeUploadCmds()
                    }
                }
                "com.daedalus.notes.START_RECORDING" -> {
                    Log.i("DaedalusADB", "Device start-recording triggered")
                    lifecycleScope.launch { deviceViewModel.bleManager.startDeviceRecording() }
                }
                "com.daedalus.notes.STOP_RECORDING" -> {
                    Log.i("DaedalusADB", "Device stop-recording triggered")
                    lifecycleScope.launch { deviceViewModel.bleManager.stopDeviceRecording() }
                }
                "com.daedalus.notes.ANALYZE" -> {
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Analyze triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        lifecycleScope.launch { recordingViewModel.analyze(filename) }
                    }
                }
                "com.daedalus.notes.DELETE_FILE" -> {
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
            val filter = IntentFilter().apply {
                addAction("com.daedalus.notes.SYNC")
                addAction("com.daedalus.notes.PROBE")
                addAction("com.daedalus.notes.PROBE2")
                addAction("com.daedalus.notes.PROBE_DELETE")
                addAction("com.daedalus.notes.PROBE_UPLOAD")
                addAction("com.daedalus.notes.START_RECORDING")
                addAction("com.daedalus.notes.STOP_RECORDING")
                addAction("com.daedalus.notes.ANALYZE")
                addAction("com.daedalus.notes.DELETE_FILE")
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

        setContent {
            DaedalusTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavGraph(
                        navController = navController,
                        deviceViewModel = deviceViewModel,
                        recordingViewModel = recordingViewModel
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
