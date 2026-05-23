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
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.lifecycleScope
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
                    Log.i("DaedalusADB", "ADB Sync triggered")
                    recordingViewModel.fullAutoSync()
                }
                "com.daedalus.notes.PROBE" -> {
                    Log.i("DaedalusADB", "BLE probe triggered")
                    if (BuildConfig.DEBUG) wifiScan()
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
            }
        }
    }

    private fun wifiScan() {
        val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
        Log.i("FW920_PROBE", "=== NEARBY WI-FI NETWORKS (cached scan) ===")
        wifi.scanResults.sortedByDescending { it.level }.forEach { r ->
            Log.i("FW920_PROBE", "  SSID=\"${r.SSID}\" BSSID=${r.BSSID} ${r.level} dBm")
        }
        if (wifi.scanResults.isEmpty()) Log.i("FW920_PROBE", "  (no cached results — check system Wi-Fi scan)")
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        val filter = IntentFilter().apply {
            addAction("com.daedalus.notes.SYNC")
            addAction("com.daedalus.notes.PROBE")
            addAction("com.daedalus.notes.PROBE2")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(adbReceiver, filter)
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
