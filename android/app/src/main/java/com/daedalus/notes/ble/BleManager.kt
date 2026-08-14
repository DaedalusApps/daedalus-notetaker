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
import com.daedalus.notes.BuildConfig
import com.daedalus.notes.data.model.Mp3FrameScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// State definitions
// ---------------------------------------------------------------------------

/**
 * Used until [android.bluetooth.BluetoothGattCallback.onMtuChanged] reports the real one.
 * This must be the pessimistic BLE spec default (23, i.e. 20-byte payloads), not the
 * value the FW920 grants on a successful negotiation (247).
 */
private const val FALLBACK_MTU = 23

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR }

data class BleState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceSerial: String = "",
    val deviceMac: String = "",
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

    /** Known devices (MAC + serial) and the user's device selection (issue #82). */
    val deviceRegistry = DeviceRegistry(context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE))

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var leScanner: BluetoothLeScanner? = null
    private var pollJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var initJob: Job? = null

    /** Single-consumer channel; responses flow here from onCharacteristicChanged. */
    private val responseChannel = Channel<ParsedResponse>(capacity = Channel.UNLIMITED)

    /** Signals completion of each writeDescriptor call (one per notification enable). */
    private val descriptorChannel = Channel<Unit>(capacity = Channel.UNLIMITED)

    // Descriptor UUID required to enable notifications on Android
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ------------------------------------------------------------------
    // Scan
    // ------------------------------------------------------------------

    /** Scan timeout: if the FW920 is not found within this duration the scan stops. */
    private val SCAN_TIMEOUT_MS = 15_000L

    fun startScan() {
        // Guard: don't start a second scan if one is already running.
        if (_bleState.value.connectionState == ConnectionState.SCANNING) {
            Log.d("BleManager", "startScan() called while already scanning — ignored")
            return
        }

        _bleState.update { it.copy(connectionState = ConnectionState.SCANNING, errorMessage = "") }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
        leScanner = btManager.adapter?.bluetoothLeScanner

        val targetMac = scanTargetMac(deviceRegistry.selectedMac.value)
        val filter = if (targetMac != null) {
            ScanFilter.Builder().setDeviceAddress(targetMac).build()
        } else {
            ScanFilter.Builder().setDeviceName(FW920_NAME).build()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        leScanner?.startScan(listOf(filter), settings, scanCallback)

        // Auto-cancel the scan after SCAN_TIMEOUT_MS if the device is never found.
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_bleState.value.connectionState == ConnectionState.SCANNING) {
                Log.d("BleManager", "Scan timed out after ${SCAN_TIMEOUT_MS}ms — stopping")
                val targetedMac = scanTargetMac(deviceRegistry.selectedMac.value) != null
                stopScan()
                _bleState.update { it.copy(errorMessage = scanTimeoutMessage(targetedMac)) }
            }
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        try {
            leScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e("BleManager", "SecurityException stopping scan", e)
        } catch (e: Exception) {
            Log.e("BleManager", "Error stopping scan", e)
        }
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
            // Go to DISCONNECTED (not ERROR) so the auto-connect LaunchedEffect can
            // retry cleanly without leaving a permanent error banner on screen.
            Log.w("BleManager", "Scan failed with error code $errorCode")
            scanTimeoutJob?.cancel()
            scanTimeoutJob = null
            leScanner = null
            _bleState.update {
                it.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    errorMessage    = ""
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Connect
    // ------------------------------------------------------------------

    private fun connect(device: BluetoothDevice) {
        // Reset the previous unit's serial at connect-start — otherwise a device swap
        // whose serial read then times out leaves the new device permanently mislabeled
        // with the old one's serial (and its files silently skipped by future syncs).
        _bleState.update {
            it.copy(connectionState = ConnectionState.CONNECTING, deviceMac = device.address, deviceSerial = "")
        }
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
        stopScan()
        stopPoller()
        initJob?.cancel()
        initJob = null
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
            if (gatt != bluetoothGatt) {
                // Stale callback from a connection superseded by a newer connect()/disconnect()
                // (e.g. a device swap) — release its resources but don't touch current state.
                if (newState == BluetoothProfile.STATE_DISCONNECTED) gatt.close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    stopPoller()
                    writeChar = null
                    gatt.close()
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
            // Tracked in initJob so a disconnect()/device swap mid-sequence can cancel it —
            // otherwise it keeps draining responseChannel for the superseded device and can
            // mark the new connection CONNECTED prematurely at the tail of its own sequence.
            initJob?.cancel()
            initJob = scope.launch {
                for (notifyUuid in listOf(NOTIFY_B0B2_UUID, NOTIFY_B0B3_UUID, NOTIFY_B0B4_UUID)) {
                    val notifyChar = service.getCharacteristic(UUID.fromString(notifyUuid)) ?: continue
                    enableNotification(gatt, notifyChar)
                    // Wait up to 2s for onDescriptorWrite before continuing to the next one
                    withTimeoutOrNull(2000L) { descriptorChannel.receive() }
                }
                Log.i("BleManager", "All notifications enabled, starting init sequence")
                runInitSequence()
            }
            initJob?.invokeOnCompletion { initJob = null }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i("BleManager", "MTU changed to $mtu (status=$status)")
            // A failed negotiation can still report a candidate mtu; keep the last-known-good
            // value instead so the BleAudit log line doesn't report a bogus mtu.
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            gatt.discoverServices()
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
            handleIncoming(gatt, characteristic.uuid.toString(), characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(gatt, characteristic.uuid.toString(), value)
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

    private fun handleIncoming(gatt: BluetoothGatt, characteristicUuid: String, data: ByteArray) {
        // Per-notification hex dump is debug-only: isMinifyEnabled is false, so Log.d ships in
        // release, and a full download is ~45,000 notifications — logging every payload evicts
        // the BleAudit summary, frameScan result, and the B0B3 warning below from the circular
        // log buffer before anyone can read them. Build the hex string only when it'll be used.
        if (BuildConfig.DEBUG) {
            val hex = data.joinToString(" ") { "%02X".format(it) }
            Log.d("BleManager", "RX char=$characteristicUuid [${data.size}b]: $hex")
        }
        // Audio data arrives on B0B3/B0B4, control responses on B0B2 — route on the
        // characteristic, not the packet prefix, so an audio chunk that coincidentally begins
        // A0 0A is never misparsed as a control packet (#96). Confirmed on hardware 2026-08-12:
        // both download acks arrived on B0B2 ("RX char=0000b0b2-... [12b]: A0 0A 01 0B 05 00 00
        // 00 F4 D8 45 E1" for ready, "RX char=0000b0b2-... [8b]: A0 0A 01 0B 01 02 70 CE" for
        // EOF), a full 62,680-byte download round-tripped byte-identical (MD5
        // 7eb5e3a9a1c8c886642e748c56f97727), and B0B3 delivered no data at all during that run.
        val isB0B3 = characteristicUuid.equals(NOTIFY_B0B3_UUID, ignoreCase = true)
        if (isB0B3 && !b0b3EverObserved) {
            b0b3EverObserved = true
            val hex = data.joinToString(" ") { "%02X".format(it) }
            Log.w("BleManager", "B0B3 delivered data for the first time ever observed " +
                "[${data.size}b]: $hex — treated as audio; if this is actually a control " +
                "packet, audio streams will be corrupted")
        }
        val isAudioChannel = isB0B3 || characteristicUuid.equals(NOTIFY_B0B4_UUID, ignoreCase = true)
        val parsed = parseResponse(data, isAudioChannel) ?: return
        Log.d("BleManager", "RX parsed: $parsed")

        // Eagerly update state based on parsed response
        when (parsed) {
            is ParsedResponse.Serial  -> {
                _bleState.update { it.copy(deviceSerial = parsed.value) }
                // Use the delivering gatt's own device, not the mutable bluetoothGatt field —
                // during a fast device swap an in-flight notification from the old device would
                // otherwise register the old serial under the new device's MAC.
                gatt.device?.address?.let { mac -> deviceRegistry.upsert(mac, parsed.value) }
            }
            is ParsedResponse.FwVersion -> _bleState.update { it.copy(fwVersion = parsed.value) }
            is ParsedResponse.Status  -> mergeStatus(parsed)
            is ParsedResponse.RecordingStarted -> _bleState.update { it.copy(isRecording = true) }
            is ParsedResponse.RecordingStopped -> _bleState.update { it.copy(isRecording = false) }
            is ParsedResponse.Ack -> {
                if (parsed.cmd == 0x07 || parsed.cmd == 0x08) {
                    _bleState.update { it.copy(isRecording = false) }
                }
            }
            is ParsedResponse.Unknown -> {
                if (parsed.cmd == 0x07 || parsed.cmd == 0x08) {
                    _bleState.update { it.copy(isRecording = false) }
                }
            }
            else -> Unit
        }
        responseChannel.trySend(parsed)
    }

    private fun mergeStatus(resp: ParsedResponse.Status) {
        val s = resp.status
        _bleState.update { current ->
            current.copy(
                batteryPct     = if (s.batteryPct > 0) s.batteryPct else current.batteryPct,
                storageFreeKb  = if (s.storageFreeKb > 0) s.storageFreeKb else current.storageFreeKb,
                storageTotalKb = if (s.storageTotalKb > 0) s.storageTotalKb else current.storageTotalKb,
                isRecording    = if (resp.cmd == 0x05) {
                    if (s.fwName == "xink_test" || current.fwVersion == "xink_test") {
                        s.isRecording || current.isRecording
                    } else {
                        s.isRecording
                    }
                } else current.isRecording,
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

    private suspend fun runInitSequence() = withLink {
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
        collectFileListLocked()

        // Only mark CONNECTED if the physical link is still up
        if (bluetoothGatt != null) {
            Log.i("BleManager", "runInitSequence: complete, marking CONNECTED")
            _bleState.update { it.copy(connectionState = ConnectionState.CONNECTED) }
            startPoller()
        } else {
            Log.w("BleManager", "runInitSequence: gatt is null after init — device disconnected mid-sequence")
        }
    }

    /**
     * True once B0B3 has delivered any notification. B0B3 was never observed carrying data
     * (GEMINI.md) but is still classified as an audio channel by [handleIncoming]; this flag
     * gates a one-time warning log so a first-ever B0B3 payload is surfaced instead of silently
     * treated as audio.
     */
    @Volatile
    private var b0b3EverObserved = false

    /**
     * Set while a file transfer owns the link, cleared inside the same [withLink]-guarded
     * critical section. linkMutex is what actually prevents a command from reaching the FW920
     * mid-transfer now (#144) — this flag is no longer load-bearing for correctness, it exists
     * purely so the 15s status poller can skip a poll outright (see [startPoller]) instead of
     * suspending on linkMutex and queueing behind a multi-minute download. `linkMutex.isLocked`
     * (checked alongside this flag, see [startPoller]) generalises the same skip-instead-of-queue
     * optimisation to every held link operation, not just transfers.
     */
    @Volatile
    private var transferInProgress = false

    /** Last MTU the device granted; 247 in practice. Used only for the BleAudit log line. */
    @Volatile
    private var negotiatedMtu = FALLBACK_MTU

    private fun startPoller() {
        stopPoller()
        pollJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(15000L)
                if (transferInProgress) {
                    Log.d("BleManager", "poller: transfer in progress, skipping status")
                    continue
                }
                // linkMutex now serialises EVERY link operation (#144), not just transfers —
                // without this check a poll would still get in, it would just suspend on
                // linkMutex and queue up behind whatever is holding it (see withLink's KDoc).
                // A status poll is disposable (the next one is 15s away regardless), so skip
                // outright rather than adding an indefinite wait to the poller's coroutine.
                if (linkMutex.isLocked) {
                    Log.d("BleManager", "poller: link busy, skipping status")
                    continue
                }
                refreshStatus()
            }
        }
    }

    private fun stopPoller() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Polls CMD 0x05 to sync the actual recording state from the device. */
    suspend fun refreshRecordingStatus() {
        refreshStatus()
    }

    /**
     * Debug-only: tell the FW920 to start a live recording on its own mic (CMD 0x06). Like every
     * [withLink]-guarded op, this suspends until the link is free — e.g. it will queue behind an
     * in-flight downloadFile() rather than run concurrently with it. That's the intended
     * tradeoff: before #144 an unguarded send here during a download killed the transfer outright
     * (the FW920 answered this instead of the audio stream); queueing is strictly better.
     */
    suspend fun startDeviceRecording(): Unit = withLink {
        Log.i("BleManager", "startDeviceRecording: ${sendAndAwait(PKT_START_RECORDING, expectedCmd = 0x06)}")
    }

    /**
     * Debug-only: tell the FW920 to stop the live recording and persist the file (CMD 0x08).
     * Queues behind any in-flight link operation, same tradeoff as [startDeviceRecording].
     */
    suspend fun stopDeviceRecording(): Unit = withLink {
        Log.i("BleManager", "stopDeviceRecording: ${sendAndAwait(PKT_STOP_RECORDING, expectedCmd = 0x08)}")
    }

    // ------------------------------------------------------------------
    // Unknown service probe
    // ------------------------------------------------------------------

    suspend fun runServiceProbe() = withLink {
        val gatt = bluetoothGatt ?: run { Log.e("FW920_PROBE", "Not connected"); return@withLink }

        // The three unknown services and their write/notify UUIDs
        val targets = listOf(
            Triple("FFD0", "0000ffd1-0000-1000-8000-00805f9b34fb",
                             listOf("0000ffd2-0000-1000-8000-00805f9b34fb",
                                    "0000ffd3-0000-1000-8000-00805f9b34fb")),
            Triple("C0C0",  "0000c0c1-0000-1000-8000-00805f9b34fb",
                             listOf("0000c0c2-0000-1000-8000-00805f9b34fb")),
            Triple("E49A",  "e49a3002-f69a-11e8-8eb2-f2801f1b9fd1",
                             listOf("e49a3003-f69a-11e8-8eb2-f2801f1b9fd1"))
        )

        // Step 1 — subscribe to all notify chars in unknown services
        Log.i("FW920_PROBE", "=== SUBSCRIBING TO UNKNOWN SERVICE NOTIFICATIONS ===")
        targets.forEach { (name, _, notifyUuids) ->
            notifyUuids.forEach { notifyUuid ->
                val svcUuid = when (name) {
                    "FFD0" -> "0000ffd0-0000-1000-8000-00805f9b34fb"
                    "C0C0" -> "0000c0c0-0000-1000-8000-00805f9b34fb"
                    else   -> "e49a3001-f69a-11e8-8eb2-f2801f1b9fd1"
                }
                val svc = gatt.getService(UUID.fromString(svcUuid))
                val ch  = svc?.getCharacteristic(UUID.fromString(notifyUuid)) ?: return@forEach
                if (ch.properties and 0x10 != 0 || ch.properties and 0x20 != 0) {
                    enableNotification(gatt, ch)
                    withTimeoutOrNull(2000L) { descriptorChannel.receive() }
                    Log.i("FW920_PROBE", "  Subscribed to $name/$notifyUuid")
                }
            }
        }
        kotlinx.coroutines.delay(500)

        // Step 2 — for each unknown write char, try multiple payloads
        val payloads = listOf(
            "our CMD proto" to PKT_GET_STATUS,           // CMD 0x05 — known good packet
            "our CMD proto" to PKT_GET_FW_VERSION,       // CMD 0x02
            "our CMD proto" to PKT_GET_SERIAL,           // CMD 0x01
            "raw 0x00"      to byteArrayOf(0x00),
            "raw 0x01"      to byteArrayOf(0x01),
            "raw 0xFF"      to byteArrayOf(0xFF.toByte()),
            "raw AT"        to "AT\r\n".toByteArray(),   // ESP32 AT command firmware
            "raw AT+GMR"    to "AT+GMR\r\n".toByteArray(), // firmware version
            "raw AT+CWLAP"  to "AT+CWLAP\r\n".toByteArray(), // list Wi-Fi APs
        )

        targets.forEach { (name, writeUuid, _) ->
            val svcUuid = when (name) {
                "FFD0" -> "0000ffd0-0000-1000-8000-00805f9b34fb"
                "C0C0" -> "0000c0c0-0000-1000-8000-00805f9b34fb"
                else   -> "e49a3001-f69a-11e8-8eb2-f2801f1b9fd1"
            }
            val svc       = gatt.getService(UUID.fromString(svcUuid)) ?: run {
                Log.w("FW920_PROBE", "$name: service not found"); return@forEach
            }
            val writeChar = svc.getCharacteristic(UUID.fromString(writeUuid)) ?: run {
                Log.w("FW920_PROBE", "$name: write char not found"); return@forEach
            }

            Log.i("FW920_PROBE", "=== PROBING SERVICE $name (write=${writeUuid.take(8)}) ===")
            payloads.forEach { (label, data) ->
                Log.d("FW920_PROBE", "$name: sending [$label] ${data.size}b")

                // Write to the unknown characteristic directly (not via sendPacket)
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(writeChar, data,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    writeChar.value = data
                    @Suppress("DEPRECATION")
                    writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    if (gatt.writeCharacteristic(writeChar)) 0 else 1
                }

                // Drain the response channel for up to 800ms — log anything that arrives
                val deadline = System.currentTimeMillis() + 800L
                while (System.currentTimeMillis() < deadline) {
                    val resp = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
                        responseChannel.receive()
                    } ?: break
                    val hex = when (resp) {
                        is ParsedResponse.Unknown -> resp.payload.joinToString(" ") { "%02X".format(it) }
                        is ParsedResponse.AudioChunk -> resp.data.take(16).joinToString(" ") { "%02X".format(it) } + "..."
                        else -> resp.toString()
                    }
                    Log.i("FW920_PROBE", "  $name [$label] → $hex")
                }
                kotlinx.coroutines.delay(200)
            }
        }
        Log.i("FW920_PROBE", "=== SERVICE PROBE COMPLETE ===")
    }

    // ------------------------------------------------------------------
    // Diagnostic probe — triggered via ADB broadcast
    // ------------------------------------------------------------------

    suspend fun runProbe() = withLink {
        val gatt = bluetoothGatt ?: run {
            Log.e("FW920_PROBE", "Not connected — connect first")
            return@withLink
        }

        Log.i("FW920_PROBE", "=== GATT SERVICE INVENTORY ===")
        gatt.services.forEach { svc ->
            Log.i("FW920_PROBE", "SERVICE ${svc.uuid} (type=${svc.type})")
            svc.characteristics.forEach { ch ->
                val props = buildString {
                    if (ch.properties and 0x02 != 0) append("READ ")
                    if (ch.properties and 0x04 != 0) append("WRITE_NO_RSP ")
                    if (ch.properties and 0x08 != 0) append("WRITE ")
                    if (ch.properties and 0x10 != 0) append("NOTIFY ")
                    if (ch.properties and 0x20 != 0) append("INDICATE ")
                    if (ch.properties and 0x80 != 0) append("EXT_PROPS ")
                }
                Log.i("FW920_PROBE", "  CHAR ${ch.uuid}  [$props]")
                ch.descriptors.forEach { desc ->
                    Log.i("FW920_PROBE", "    DESC ${desc.uuid}")
                }
            }
        }

        Log.i("FW920_PROBE", "=== UNDOCUMENTED COMMAND PROBE (0x19–0x50) ===")
        for (cmd in 0x19..0x50) {
            sendPacket(buildPacket(cmd))
            val resp = withTimeoutOrNull(600L) { awaitResponse(cmd) }
            when {
                resp == null -> Log.d("FW920_PROBE", "CMD 0x${cmd.toString(16).uppercase()} → timeout")
                resp is ParsedResponse.Unknown -> {
                    val hex = resp.payload.joinToString(" ") { "%02X".format(it) }
                    val str = resp.payload.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
                    Log.i("FW920_PROBE", "CMD 0x${cmd.toString(16).uppercase()} → UNKNOWN payload=[$hex] str=\"$str\"")
                }
                else -> Log.i("FW920_PROBE", "CMD 0x${cmd.toString(16).uppercase()} → $resp")
            }
            kotlinx.coroutines.delay(150)
        }
        Log.i("FW920_PROBE", "=== PROBE COMPLETE ===")
    }

    // ------------------------------------------------------------------
    // Public suspend methods
    // ------------------------------------------------------------------

    suspend fun refreshStatus(): Unit = withLink {
        sendAndAwait(PKT_GET_STATUS, expectedCmd = 0x05)
    }

    suspend fun deleteFile(filename: String): Boolean = withLink {
        Log.i("BleManager", "deleteFile: '$filename'")
        // Two-phase delete: first 0x0D stages (payload=[0]), second 0x0D commits (payload=[1])
        val stage = sendAndAwait(buildDeleteFile(filename), expectedCmd = 0x0D)
        Log.i("BleManager", "deleteFile: stage=$stage")
        if (stage == null) {
            // The stage exchange timed out. Sending the commit packet anyway would race a
            // late-arriving stage ack (payload=[0]) against the real commit ack: if the stage ack
            // finally arrives after commit is sent, the commit's own awaitResponse(0x0D) would
            // return it instead, misreading a stage ack's payload=[0] as a FAILED commit and
            // reporting deleted=false even though the device actually deleted the file. Bail out
            // instead of racing it.
            Log.w("BleManager", "deleteFile: stage timed out, not sending commit")
            return@withLink false
        }
        // Defence in depth for a stage ack that arrives late but still before commit is sent
        // (e.g. a device retransmit) — a duplicate cmd=0x0D response sitting in the channel would
        // otherwise be misread as the commit response the same way a truly-late one would be.
        drainResidue()
        val commit = sendAndAwait(buildDeleteFile(filename), expectedCmd = 0x0D)
        Log.i("BleManager", "deleteFile: commit=$commit")
        val deleted = commit is ParsedResponse.Unknown &&
                commit.cmd == 0x0D &&
                commit.payload.firstOrNull()?.toInt() == 1
        if (!deleted) {
            Log.w("BleManager", "deleteFile: commit failed, payload=${
                (commit as? ParsedResponse.Unknown)?.payload?.toList()}")
            return@withLink false
        }
        collectFileListLocked()
        val cleanName = if (filename.endsWith(".mp3")) filename.removeSuffix(".mp3") else filename
        val stillPresent = _bleState.value.files.any { it.filename.equals(cleanName, ignoreCase = true) }
        Log.i("BleManager", "deleteFile: stillPresent=$stillPresent")
        !stillPresent
    }

    /** Local-only stats handed out of downloadFile's [withLink] block for post-lock logging. */
    private data class DownloadOutcome(
        val totalBytes: Long,
        val chunkCount: Int,
        val chunkSizes: Map<Int, Int>,
        val first60ChunkSizes: List<Int>
    )

    suspend fun downloadFile(filename: String, onProgress: (Long) -> Unit): File? {
        val context = this.context
        val cleanName = if (filename.endsWith(".mp3")) filename.removeSuffix(".mp3") else filename

        val localDir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
        val safeName = File(cleanName).name + ".mp3"
        val localFile = File(localDir, safeName).also { it.delete() }

        // Only the wire transfer itself needs linkMutex — everything after fos.close() below
        // (frame-scan re-read, log-string building, zero-byte cleanup) is pure local disk/CPU
        // work with no responseChannel traffic, and running it inside the lock would block every
        // other queued link consumer for as long as it takes.
        val outcome = withLink {
            var totalBytes = 0L
            var chunkCount = 0
            val chunkSizes = mutableMapOf<Int, Int>()
            // Ordering the histogram loses is what makes the block structure invisible in logs; this
            // keeps the first 60 chunk sizes in arrival order so the structure can be confirmed
            // directly, capped so the log line stays bounded regardless of file size.
            val first60ChunkSizes = mutableListOf<Int>()
            val fos = FileOutputStream(localFile)
            transferInProgress = true

            // Protocol: send CMD 0x0B → device responds Ack(0x0B) "ready" → streams AudioChunks →
            // signals Ack(0x0B) again when done. We treat the second Ack(0x0B) (after data) as EOF.
            //
            // buildDownloadFile() below can theoretically throw (buildPacket's require), and doing
            // so here would skip the `totalBytes == 0L` cleanup further down, orphaning the 0-byte
            // localFile just created above. That is guarded, not by ordering, but by
            // MAX_PROTOCOL_FILENAME_CHARS + FW920Protocol.kt's init-time bound check keeping every
            // possible payload under buildPacket's 255-byte ceiling (#116 finding 2) — so this can
            // never actually throw. If that guarantee ever changes, this call site needs to move
            // ahead of the file creation above.
            try {
                val pkt = buildDownloadFile(filename)
                Log.i("BleManager", "downloadFile: CMD 0x0B '$cleanName'")
                sendPacket(pkt)

                var readyReceived = false
                val timeoutMs = 10000L
                var lastDataTime = System.currentTimeMillis()

                outer@ while (System.currentTimeMillis() - lastDataTime < timeoutMs) {
                    val response = withTimeoutOrNull(2000) { responseChannel.receive() }
                    if (response == null) {
                        Log.d("BleManager", "downloadFile: 2s idle, totalBytes=$totalBytes")
                        continue
                    }

                    when (response) {
                        is ParsedResponse.AudioChunk -> {
                            // An empty chunk must not latch readyReceived: if it arrived between
                            // CMD 0x0B and the ready Ack(0x0B), latching here would make that ready
                            // ack read as the end-of-file ack below, ending the download at 0 bytes.
                            if (response.data.isNotEmpty()) readyReceived = true

                            fos.write(response.data)
                            // The histogram these feed was previously logged from variables nothing
                            // ever wrote to, so every transfer reported "0 chunks" and an empty map.
                            chunkCount++
                            chunkSizes[response.data.size] = (chunkSizes[response.data.size] ?: 0) + 1
                            if (first60ChunkSizes.size < 60) first60ChunkSizes.add(response.data.size)
                            totalBytes += response.data.size
                            onProgress(totalBytes)
                            lastDataTime = System.currentTimeMillis()
                            if (totalBytes % (64 * 1024) < response.data.size) {
                                Log.d("BleManager", "downloadFile: $totalBytes bytes received")
                            }
                        }
                        is ParsedResponse.Ack -> {
                            Log.i("BleManager", "downloadFile: Ack cmd=0x${response.cmd.toString(16)} totalBytes=$totalBytes readyReceived=$readyReceived")
                            when (response.cmd) {
                                0x07 -> break@outer
                                0x0B -> {
                                    if (!readyReceived) {
                                        // Initial "ready" Ack — keep waiting for data
                                        lastDataTime = System.currentTimeMillis()
                                    } else {
                                        // End-of-file Ack
                                        break@outer
                                    }
                                }
                            }
                        }
                        else -> Log.d("BleManager", "downloadFile: unexpected=$response")
                    }
                }
            } finally {
                transferInProgress = false
                fos.close()
            }

            DownloadOutcome(totalBytes, chunkCount, chunkSizes, first60ChunkSizes)
        }

        val (totalBytes, chunkCount, chunkSizes, first60ChunkSizes) = outcome
        Log.i("BleManager", "downloadFile: done '$cleanName', totalBytes=$totalBytes")
        // A raw byte stream should be almost entirely one MTU-sized chunk repeated, with a
        // single odd-sized tail. Several distinct sizes, or a size that never matches the MTU,
        // would mean the device frames its payloads and we are storing the framing as audio.
        var frameScanSuffix = ""
        if (totalBytes > 0) {
            try {
                val scanResult = Mp3FrameScan.scan(localFile)
                frameScanSuffix = ", frameScan=${scanResult.framesOk} frames, " +
                    "${scanResult.gapCount} gaps, ${scanResult.gapBytes} bytes " +
                    "(${String.format(java.util.Locale.US, "%.2f", scanResult.gapPercent)}%)"
            } catch (e: Throwable) {
                Log.w("BleAudit", "frameScan failed for '$cleanName': ${e.message}")
            }
        }
        Log.i(
            "BleAudit",
            "transfer done: $chunkCount chunks, $totalBytes bytes, mtu=$negotiatedMtu, " +
                "size histogram=" +
                chunkSizes.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}B x${it.value}" } +
                ", first60=" + first60ChunkSizes.joinToString(",") +
                frameScanSuffix
        )
        if (totalBytes == 0L) {
            // fos is already closed (finally block above ran before we get here), so it's safe
            // to delete now. Leaving this 0-byte file behind would occupy the filename and be
            // mistaken for a real (if empty) recording by anything that just checks existence.
            val deleted = localFile.delete()
            Log.w("BleManager", "downloadFile: zero-byte transfer for '$cleanName', deleted empty file (success=$deleted)")
        }
        return if (totalBytes > 0) localFile else null
    }

    // Add FileOutputStream import later or here if I can

    suspend fun listFiles() = withLink {
        collectFileListLocked()
    }



    /** Probes CMD range 0x0D–0x17 with a filename payload to find the real delete command. */
    suspend fun probeDeleteCmds(filename: String) = withLink {
        val cleanName = if (filename.endsWith(".mp3")) filename.removeSuffix(".mp3") else filename
        // Clamped like buildDeleteFile/buildDownloadFile (#116 finding 3): unclamped, a
        // pathologically long filename here would blow past buildPacket's 255-byte payload
        // ceiling and throw out of this suspend fun instead of silently corrupting the wire
        // protocol like the other two builders used to.
        val nameBytes = cleanName.take(MAX_PROTOCOL_FILENAME_CHARS).padEnd(14, ' ').toByteArray(Charsets.US_ASCII)
        val skipKnown = setOf(0x0F)  // 0x0F is the periodic status update, skip it

        for (cmd in 0x0D..0x17) {
            if (cmd in skipKnown) continue
            Log.i("DeleteProbe", "Trying CMD 0x${cmd.toString(16).uppercase()} with filename '$cleanName'")
            val pkt = buildPacket(cmd, nameBytes)
            sendPacket(pkt)
            val resp = withTimeoutOrNull(1500L) { awaitResponse(cmd) }
            Log.i("DeleteProbe", "CMD 0x${cmd.toString(16).uppercase()} response: $resp")
            kotlinx.coroutines.delay(300)

            collectFileListLocked()
            val gone = _bleState.value.files.none { it.filename.equals(cleanName, ignoreCase = true) }
            Log.i("DeleteProbe", "CMD 0x${cmd.toString(16).uppercase()} file gone=$gone")
            if (gone) {
                Log.i("DeleteProbe", "*** FOUND DELETE CMD: 0x${cmd.toString(16).uppercase()} ***")
                return@withLink
            }
        }
        Log.i("DeleteProbe", "No delete command found in 0x0D-0x17 range")
    }

    /**
     * SPIKE: probe for an UPLOAD command (app→device file write). Every known source
     * (GEMINI.md protocol table, the deleted Python prototype, the 0x19–0x50 probe) shows the
     * FW920 protocol is download-only. This tries each candidate opcode with a filename+size
     * "begin upload" payload, streams a small dummy buffer + a candidate end marker, then checks
     * whether the file appears via listFiles(). Logs under tag "UploadProbe".
     * Run on real hardware (device connected): adb broadcast com.daedalus.notes.PROBE_UPLOAD
     */
    suspend fun probeUploadCmds() = withLink {
        val testName  = "UPLOADTEST01"
        val nameBytes = testName.padEnd(14, ' ').take(14).toByteArray(Charsets.US_ASCII)
        val dummy     = ByteArray(512) { (it and 0xFF).toByte() }
        val sizeLe    = byteArrayOf(
            (dummy.size and 0xFF).toByte(),
            ((dummy.size ushr 8) and 0xFF).toByte(),
            ((dummy.size ushr 16) and 0xFF).toByte(),
            ((dummy.size ushr 24) and 0xFF).toByte()
        )
        val skip = setOf(0x0F, 0x15, 0x18, 0x1A)  // periodic status / known-mapped opcodes
        for (cmd in (0x0E..0x50).filter { it !in skip }) {
            Log.i("UploadProbe", "Trying CMD 0x${cmd.toString(16).uppercase()} begin-upload (name+size)")
            sendPacket(buildPacket(cmd, nameBytes + sizeLe))
            val resp = withTimeoutOrNull(1200L) { responseChannel.receive() }
            Log.i("UploadProbe", "  begin resp: $resp")
            if (resp is ParsedResponse.Ack && resp.cmd == cmd) {
                sendPacket(dummy)                        // candidate raw data chunk
                sendPacket(buildPacket(cmd, nameBytes))  // candidate end-of-upload marker
                kotlinx.coroutines.delay(300)
            }
            kotlinx.coroutines.delay(200)
            collectFileListLocked()
            if (_bleState.value.files.any { it.filename.equals(testName, ignoreCase = true) }) {
                Log.i("UploadProbe", "*** UPLOAD COMMAND FOUND: 0x${cmd.toString(16).uppercase()} — '$testName' is now on device ***")
                return@withLink
            }
        }
        Log.i("UploadProbe", "No upload command found in 0x0E–0x50. Protocol appears download-only.")
    }

    // ------------------------------------------------------------------
    // File list collector
    // ------------------------------------------------------------------

    /**
     * Serialises EVERY operation that talks to the FW920 over the shared, uncorrelated
     * responseChannel — not just enumeration. The FW920 is a single-command-at-a-time device and
     * responses carry no request-correlation id, so ANY two concurrent link operations can steal
     * each other's responses off responseChannel: collectFileList vs. collectFileList (#141,
     * measured on hardware as a 9/23 split with 32 entry lines for 16 unique files), the 15s
     * status poller's refreshStatus() vs. an in-flight enumeration (#141 finding 1), and — the
     * remaining gap this mutex now also covers (#144) — refreshStatus() vs. deleteFile()'s own
     * two 0x0D stage/commit exchanges (a stolen commit ack makes a successful hardware delete
     * report "Failed to delete"), and any enumeration vs. downloadFile()'s audio stream (a stolen
     * AudioChunk silently corrupts the download while it still reports success). Every public
     * suspend entry point that sends a packet and waits on responseChannel — refreshStatus,
     * deleteFile, downloadFile, listFiles, runInitSequence, startDeviceRecording,
     * stopDeviceRecording, runProbe, runServiceProbe, probeDeleteCmds, probeUploadCmds — acquires
     * this lock via [withLink] for its entire operation, so at most one of them is ever draining
     * responseChannel at a time. This is a per-instance property (not in a companion object), so
     * two BleManager instances never contend on the same lock — that would over-synchronise
     * unrelated devices (e.g. during a device swap).
     *
     * Kotlin's Mutex is NON-REENTRANT. Several guarded operations internally enumerate files
     * (deleteFile, runInitSequence, probeDeleteCmds, probeUploadCmds) — while already holding this
     * lock via their own [withLink] call, re-acquiring it would deadlock. So callers already
     * holding the lock invoke [collectFileListLocked] directly instead of going through
     * [withLink] again. No guarded call site invokes another guarded (lock-acquiring) call site
     * while already holding the lock, so this remains deadlock-free.
     */
    private val linkMutex = Mutex()

    /**
     * Runs [block] with [linkMutex] held, after draining any residue left in responseChannel by a
     * PREVIOUS operation that gave up on its own timeout (e.g. collectFileList's per-item idle
     * timeout, or downloadFile's idle timeout) — responseChannel has unlimited capacity and
     * nothing else drains it between one guarded operation and the next, so a prior operation's
     * still-arriving responses would otherwise be consumed here as if they belonged to THIS
     * operation's own request, corrupting its result with stale data (#141 finding 2, generalised
     * to every link operation by #144). None of the responses on this channel carry a
     * request-correlation id, so an unconditional drain at the gate is the only mechanism
     * available.
     *
     * Queueing: because linkMutex is a single per-instance gate, any user-initiated op called
     * while another is in flight suspends until the link frees — e.g. a delete requested during
     * an in-flight multi-minute download waits for that download to finish before it even sends
     * its first packet. This is inherent to the FW920 being single-command-at-a-time, not a
     * regression: pre-#144, sending during a transfer didn't queue, it corrupted the link (see
     * [transferInProgress]'s KDoc). This function intentionally adds no timeout of its own — a
     * caller that needs to bound how long it waits (e.g. to show the user a "busy" state) has to
     * do that at the UI layer, out of scope here.
     */
    private suspend fun <T> withLink(block: suspend () -> T): T = linkMutex.withLock {
        drainResidue()
        block()
    }

    private fun drainResidue() {
        var drainedCount = 0
        while (responseChannel.tryReceive().isSuccess) {
            drainedCount++
        }
        if (drainedCount > 0) {
            Log.d("BleManager", "withLink: drained $drainedCount stale residue item(s) from responseChannel")
        }
    }

    /**
     * Must only be called while [linkMutex] is already held (see [withLink]). Also drains
     * residue itself, not just at the enclosing [withLink]'s entry: probeDeleteCmds and
     * probeUploadCmds call this in a LOOP under a single lock hold, so without a drain here too,
     * iteration N timing out with stragglers still in flight would have them consumed by
     * iteration N+1's enumeration as if they were its own response, corrupting it exactly like
     * the cross-call residue this same drain already prevents at the [withLink] boundary.
     */
    private suspend fun collectFileListLocked() {
        drainResidue()
        Log.i("BleManager", "collectFileList: sending PKT_LIST_FILES")
        sendPacket(PKT_LIST_FILES)
        val collected = mutableListOf<FileEntry>()
        val perItemTimeoutMs = 3000L

        while (true) {
            val response = withTimeoutOrNull(perItemTimeoutMs) {
                awaitResponse(expectedCmd = 0x0A)
            }
            if (response == null) {
                Log.w("BleManager", "collectFileList: idle timeout waiting for file entry after ${collected.size} files")
                break
            }

            when (response) {
                is ParsedResponse.FileList -> {
                    if (response.entry == null) {
                        Log.i("BleManager", "collectFileList: end-of-list, ${collected.size} files")
                        break
                    }
                    Log.i("BleManager", "collectFileList: entry=${response.entry.filename} ${response.entry.sizeBytes}B")
                    collected.add(response.entry)
                }
                is ParsedResponse.Ack -> {
                    Log.d("BleManager", "collectFileList: received Ack for cmd=${response.cmd}, continuing enumeration")
                }
                else -> {
                    Log.d("BleManager", "collectFileList: received non-FileList response $response during enumeration, continuing")
                }
            }
        }

        Log.i("BleManager", "collectFileList: done, ${collected.size} files")
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
