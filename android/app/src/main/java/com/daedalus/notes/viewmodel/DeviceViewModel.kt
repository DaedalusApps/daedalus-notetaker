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

    /**
     * Selects a known device (or null for "any device"), then reconnects using that selection.
     * The selection is always persisted; if a recording is in progress on the current device,
     * the reconnect is skipped so it isn't interrupted — the existing auto-connect effects will
     * pick up the new selection once recording ends.
     */
    fun selectDevice(mac: String?) {
        bleManager.deviceRegistry.selectDevice(mac)
        if (bleManager.bleState.value.isRecording) return
        shouldAutoConnect = true
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
