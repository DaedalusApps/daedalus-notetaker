package com.daedalus.notes.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalus.notes.ble.BleManager
import com.daedalus.notes.ble.BleState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)
    val state: StateFlow<BleState> = bleManager.bleState

    var shouldAutoConnect: Boolean = true

    fun scan() {
        shouldAutoConnect = true
        bleManager.startScan()
    }

    fun disconnect() {
        shouldAutoConnect = false
        bleManager.disconnect()
    }

    /** Selects a known device (or null for "any device"), then reconnects using that selection. */
    fun selectDevice(mac: String?) {
        shouldAutoConnect = true
        bleManager.deviceRegistry.selectDevice(mac)
        bleManager.disconnect()
        bleManager.startScan()
    }

    fun refreshFiles() = viewModelScope.launch {
        bleManager.listFiles()
    }

    fun downloadFile(filename: String, onProgress: (Long) -> Unit) = viewModelScope.launch {
        bleManager.downloadFile(filename, onProgress)
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.destroy()
    }
}
