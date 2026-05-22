package com.daedalus.notes.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// State definitions
// ---------------------------------------------------------------------------

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR }

data class BleState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceSerial: String = "",
    val fwVersion: String = "",
    val batteryPct: Int = 0,
    val storageFreeKb: Long = 0,
    val storageTotalKb: Long = 0,
    val isRecording: Boolean = false,
    val files: List<FileEntry> = emptyList(),
    val errorMessage: String = ""
)

// ---------------------------------------------------------------------------
// BleManager
// ---------------------------------------------------------------------------

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    // ------------------------------------------------------------------
    // Public state
    // ------------------------------------------------------------------

    private val _bleState = MutableStateFlow(BleState())
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var leScanner: BluetoothLeScanner? = null

    /** Single-consumer channel; responses flow here from onCharacteristicChanged. */
    private val responseChannel = Channel<ParsedResponse>(capacity = Channel.UNLIMITED)

    /** Signals completion of each writeDescriptor call (one per notification enable). */
    private val descriptorChannel = Channel<Unit>(capacity = Channel.UNLIMITED)

    // Descriptor UUID required to enable notifications on Android
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ------------------------------------------------------------------
    // Scan
    // ------------------------------------------------------------------

    fun startScan() {
        _bleState.update { it.copy(connectionState = ConnectionState.SCANNING, errorMessage = "") }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
        leScanner = btManager.adapter?.bluetoothLeScanner

        val filter = ScanFilter.Builder()
            .setDeviceName("FW920")
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        leScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        leScanner?.stopScan(scanCallback)
        leScanner = null
        if (_bleState.value.connectionState == ConnectionState.SCANNING) {
            _bleState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            _bleState.update {
                it.copy(
                    connectionState = ConnectionState.ERROR,
                    errorMessage    = "Scan failed: error $errorCode"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Connect
    // ------------------------------------------------------------------

    private fun connect(device: BluetoothDevice) {
        _bleState.update { it.copy(connectionState = ConnectionState.CONNECTING) }
        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    // ------------------------------------------------------------------
    // Disconnect
    // ------------------------------------------------------------------

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeChar     = null
        _bleState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
    }

    // ------------------------------------------------------------------
    // GATT callback
    // ------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeChar = null
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    _bleState.update {
                        it.copy(connectionState = ConnectionState.DISCONNECTED)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _bleState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage    = "Service discovery failed: $status"
                    )
                }
                return
            }

            val service = gatt.getService(UUID.fromString(SERVICE_UUID))
            if (service == null) {
                _bleState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage    = "FW920 service not found"
                    )
                }
                return
            }

            writeChar = service.getCharacteristic(UUID.fromString(WRITE_UUID))

            // Enable notifications one at a time (GATT requires serialized operations),
            // then start the init sequence only after all descriptors are written.
            scope.launch {
                for (notifyUuid in listOf(NOTIFY_B0B2_UUID, NOTIFY_B0B3_UUID, NOTIFY_B0B4_UUID)) {
                    val notifyChar = service.getCharacteristic(UUID.fromString(notifyUuid)) ?: continue
                    enableNotification(gatt, notifyChar)
                    // Wait up to 2s for onDescriptorWrite before continuing to the next one
                    withTimeoutOrNull(2000L) { descriptorChannel.receive() }
                }
                Log.i("BleManager", "All notifications enabled, starting init sequence")
                runInitSequence()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d("BleManager", "onDescriptorWrite status=$status char=${descriptor.characteristic.uuid}")
            descriptorChannel.trySend(Unit)
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleIncoming(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(value)
        }
    }

    // ------------------------------------------------------------------
    // Notification helper
    // ------------------------------------------------------------------

    private fun enableNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    // ------------------------------------------------------------------
    // Incoming data handler
    // ------------------------------------------------------------------

    private fun handleIncoming(data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it) }
        Log.d("BleManager", "RX [${data.size}b]: $hex")
        val parsed = parseResponse(data) ?: return
        Log.d("BleManager", "RX parsed: $parsed")
        // Eagerly update state for status and file-list responses
        when (parsed) {
            is ParsedResponse.Serial  -> _bleState.update { it.copy(deviceSerial = parsed.value) }
            is ParsedResponse.FwVersion -> _bleState.update { it.copy(fwVersion = parsed.value) }
            is ParsedResponse.Status  -> mergeStatus(parsed.status)
            else -> Unit
        }
        responseChannel.trySend(parsed)
    }

    private fun mergeStatus(s: DeviceStatus) {
        _bleState.update { current ->
            current.copy(
                batteryPct     = if (s.batteryPct > 0) s.batteryPct else current.batteryPct,
                storageFreeKb  = if (s.storageFreeKb > 0) s.storageFreeKb else current.storageFreeKb,
                storageTotalKb = if (s.storageTotalKb > 0) s.storageTotalKb else current.storageTotalKb,
                isRecording    = s.isRecording,
                fwVersion      = if (s.fwName.isNotEmpty()) s.fwName else current.fwVersion
            )
        }
    }

    // Extend BleState to carry fwName without breaking the public contract
    private var BleState.fwName: String
        get()      = fwVersion
        set(value) { /* intentionally no-op; used only in mergeStatus lambda */ }

    // ------------------------------------------------------------------
    // Init sequence
    // ------------------------------------------------------------------

    private suspend fun runInitSequence() {
        Log.i("BleManager", "runInitSequence: start")

        // 1. CMD 0x02 — get firmware version
        sendAndAwait(PKT_GET_FW_VERSION, expectedCmd = 0x02)
            .also { Log.d("BleManager", "CMD 0x02 (fw version): ${if (it != null) "ok" else "timeout"}") }

        // 2. CMD 0x03 — set firmware version string
        sendAndAwait(buildSetFwVersion(), expectedCmd = 0x03)
            .also { Log.d("BleManager", "CMD 0x03 (set fw): ${if (it != null) "ok" else "timeout"}") }

        // 3. CMD 0x01 — get serial
        sendAndAwait(PKT_GET_SERIAL, expectedCmd = 0x01)
            .also { Log.d("BleManager", "CMD 0x01 (serial): ${if (it != null) "ok" else "timeout"}") }

        // 4. CMD 0x04 — sync time
        sendAndAwait(buildSyncTime(), expectedCmd = 0x04)
            .also { Log.d("BleManager", "CMD 0x04 (time): ${if (it != null) "ok" else "timeout"}") }

        // 5. CMD 0x05 — get device status
        sendAndAwait(PKT_GET_STATUS, expectedCmd = 0x05)
            .also { Log.d("BleManager", "CMD 0x05 (status): ${if (it != null) "ok" else "timeout"}") }

        // 6. CMD 0x18 — unknown init command
        sendAndAwait(PKT_CMD18, expectedCmd = 0x18)
            .also { Log.d("BleManager", "CMD 0x18: ${if (it != null) "ok" else "timeout"}") }

        // 7. CMD 0x0A — list files
        collectFileList()

        // Only mark CONNECTED if the physical link is still up
        if (bluetoothGatt != null) {
            Log.i("BleManager", "runInitSequence: complete, marking CONNECTED")
            _bleState.update { it.copy(connectionState = ConnectionState.CONNECTED) }
        } else {
            Log.w("BleManager", "runInitSequence: gatt is null after init — device disconnected mid-sequence")
        }
    }

    /** Polls CMD 0x0F to sync the actual recording state from the device. */
    suspend fun refreshRecordingStatus() {
        sendAndAwait(PKT_GET_STATUS, expectedCmd = 0x05)
    }

    // ------------------------------------------------------------------
    // Public suspend methods
    // ------------------------------------------------------------------

    suspend fun startRecording(): Boolean {
        Log.i("BleManager", "startRecording: gatt=${bluetoothGatt != null} writeChar=${writeChar != null}")
        if (writeChar == null || bluetoothGatt == null) return false
        sendPacket(PKT_START_RECORDING)
        // Optimistic update — device starts recording immediately on CMD 0x06; ACK is optional
        _bleState.update { it.copy(isRecording = true) }
        // Wait briefly for optional confirmation (don't require it)
        val response = withTimeoutOrNull(2000L) { awaitResponse(0x06) }
        Log.i("BleManager", "startRecording: optional confirmation=$response")
        return true
    }

    suspend fun stopRecording(): Boolean {
        Log.i("BleManager", "stopRecording")
        val stopResp = sendAndAwait(PKT_STOP_RECORDING, expectedCmd = 0x08)
        Log.i("BleManager", "stopRecording: response=$stopResp")
        if (stopResp is ParsedResponse.RecordingStopped) {
            sendAndAwait(PKT_CONFIRM_DONE, expectedCmd = 0x07)
        }
        // Always clear recording state — user intent was to stop, even if device
        // already stopped (auto-stop on full storage, timeout, etc.)
        _bleState.update { it.copy(isRecording = false) }
        return stopResp is ParsedResponse.RecordingStopped
    }

    suspend fun refreshStatus() {
        sendAndAwait(PKT_GET_STATUS, expectedCmd = 0x05)
    }

    suspend fun downloadFile(filename: String, onProgress: (Long) -> Unit): File? {
        val context = this.context
        val cleanName = if (filename.endsWith(".mp3")) filename.removeSuffix(".mp3") else filename
        
        // Command 0x06 with payload 0x10 0x00 + filename is used for download
        val payload = byteArrayOf(0x10.toByte(), 0x00.toByte()) + 
                     cleanName.padEnd(14, ' ').take(14).toByteArray(Charsets.US_ASCII)
        val downloadPkt = buildPacket(0x06, payload)
        
        val localFile = File(context.getExternalFilesDir(null), "Recordings/$filename.mp3").also {
            it.parentFile?.mkdirs()
            it.delete() // Start fresh
        }

        sendPacket(downloadPkt)
        
        var totalBytes = 0L
        val fos = FileOutputStream(localFile)
        
        try {
            // Monitor B0B3 chunks and CMD 0x07 (Done)
            val timeoutMs = 10000L // 10s of no data = stop
            var lastDataTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - lastDataTime < timeoutMs) {
                val response = withTimeoutOrNull(2000) { responseChannel.receive() } ?: continue
                
                when (response) {
                    is ParsedResponse.AudioChunk -> {
                        fos.write(response.data)
                        totalBytes += response.data.size
                        onProgress(totalBytes)
                        lastDataTime = System.currentTimeMillis()
                    }
                    is ParsedResponse.Ack -> {
                        if (response.cmd == 0x07) break // Transfer done
                    }
                    else -> Unit
                }
            }
        } finally {
            fos.close()
        }

        return if (totalBytes > 0) localFile else null
    }

    // Add FileOutputStream import later or here if I can

    suspend fun listFiles() {
        collectFileList()
    }

    // ------------------------------------------------------------------
    // File list collector
    // ------------------------------------------------------------------

    private suspend fun collectFileList() {
        sendPacket(PKT_LIST_FILES)
        val collected = mutableListOf<FileEntry>()
        val timeoutMs = 5000L
        val deadline  = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val response  = withTimeoutOrNull(remaining) {
                awaitResponse(expectedCmd = 0x0A)
            } ?: break

            when (response) {
                is ParsedResponse.FileList -> {
                    if (response.entry == null) break   // end-of-list marker
                    collected.add(response.entry)
                }
                else -> break
            }
        }

        _bleState.update { it.copy(files = collected) }
    }

    // ------------------------------------------------------------------
    // Low-level send helpers
    // ------------------------------------------------------------------

    private suspend fun sendPacket(data: ByteArray) {
        val char = writeChar ?: run {
            Log.w("BleManager", "sendPacket: writeChar is null — device disconnected?")
            return
        }
        val gatt = bluetoothGatt ?: run {
            Log.w("BleManager", "sendPacket: gatt is null — device disconnected?")
            return
        }

        suspendCancellableCoroutine<Unit> { cont ->
            val cmd = if (data.size >= 4) data[3].toInt() and 0xFF else -1
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                if (gatt.writeCharacteristic(char)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
            Log.d("BleManager", "sendPacket cmd=0x${cmd.toString(16)} result=$result")
            cont.resume(Unit)
        }
    }

    private suspend fun sendAndAwait(
        data: ByteArray,
        expectedCmd: Int,
        timeoutMs: Long = 3000L
    ): ParsedResponse? {
        sendPacket(data)
        return withTimeoutOrNull(timeoutMs) { awaitResponse(expectedCmd) }
    }

    /**
     * Drains [responseChannel] until a response matching [expectedCmd] is found.
     * Non-matching responses are discarded (they will already have been applied to state
     * by [handleIncoming]).
     */
    private suspend fun awaitResponse(expectedCmd: Int): ParsedResponse {
        for (response in responseChannel) {
            val matchesCmd = when (response) {
                is ParsedResponse.Serial          -> expectedCmd == 0x01
                is ParsedResponse.FwVersion       -> expectedCmd == 0x02
                is ParsedResponse.Ack             -> response.cmd == expectedCmd
                is ParsedResponse.Status          -> expectedCmd == 0x05 || expectedCmd == 0x0F
                is ParsedResponse.FileList        -> expectedCmd == 0x0A
                is ParsedResponse.RecordingStarted -> expectedCmd == 0x06
                is ParsedResponse.RecordingStopped -> expectedCmd == 0x08
                is ParsedResponse.AudioChunk      -> false
                is ParsedResponse.Unknown         -> response.cmd == expectedCmd
            }
            if (matchesCmd) return response
        }
        // Channel was closed — should not happen in normal operation
        error("responseChannel closed unexpectedly")
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    fun destroy() {
        disconnect()
        scope.cancel()
        responseChannel.close()
    }
}
