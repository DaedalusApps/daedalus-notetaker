package com.daedalus.notes.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class BleManagerTest {

    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var manager: BleManager
    private lateinit var gattCallback: BluetoothGattCallback

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any() as String) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } returns null

        manager = BleManager(context)
        gattCallback = privateField(manager, "gattCallback") as BluetoothGattCallback
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun privateField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun negotiatedMtu(): Int = privateField(manager, "negotiatedMtu") as Int

    // --- FIX 5: onMtuChanged must ignore a non-success status --------------------------------

    @Test
    fun onMtuChanged_success_storesTheNegotiatedMtu() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        gattCallback.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
        assertEquals(247, negotiatedMtu())
    }

    @Test
    fun onMtuChanged_failureStatus_leavesTheExistingMtuUntouched() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val before = negotiatedMtu()
        gattCallback.onMtuChanged(gatt, 512, /* status = */ 133)
        assertEquals(before, negotiatedMtu())
    }

    // --- #96: handleIncoming's characteristic-UUID -> isAudioChannel routing -----------------

    @Suppress("UNCHECKED_CAST")
    private fun responseChannel(): Channel<ParsedResponse> =
        privateField(manager, "responseChannel") as Channel<ParsedResponse>

    private fun characteristicWithUuid(uuid: String): BluetoothGattCharacteristic {
        val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { characteristic.uuid } returns UUID.fromString(uuid)
        return characteristic
    }

    /**
     * A notification delivered on B0B4 (audio channel) that happens to satisfy the old
     * A0 0A + length-heuristic control-packet check must still be routed as audio, not control.
     */
    @Test
    fun onCharacteristicChanged_b0b4_routesAsAudioEvenWhenBytesLookLikeControl() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        // Literal, not NOTIFY_B0B4_UUID: if the UUID constants were ever transposed, using the
        // constant here would make this test pass right alongside the real misrouting.
        val characteristic = characteristicWithUuid("0000b0b4-0000-1000-8000-00805f9b34fb")
        val data = ByteArray(244) { 0x00 }
        data[0] = 0xA0.toByte()
        data[1] = 0x0A.toByte()
        data[2] = 0x01
        data[3] = 0x0B          // looks like CMD 0x0B (download ack)
        data[4] = 237.toByte()  // 5 + 237 + 2 == 244 -> satisfies the old length heuristic

        gattCallback.onCharacteristicChanged(gatt, characteristic, data)

        val result = responseChannel().tryReceive().getOrNull()
        assertTrue("Expected AudioChunk, got $result", result is ParsedResponse.AudioChunk)
    }

    /** A notification delivered on B0B2 (control channel) is routed as a control packet. */
    @Test
    fun onCharacteristicChanged_b0b2_routesAsControl() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val characteristic = characteristicWithUuid("0000b0b2-0000-1000-8000-00805f9b34fb")
        val eofAck = buildPacket(0x0B)

        gattCallback.onCharacteristicChanged(gatt, characteristic, eofAck)

        val result = responseChannel().tryReceive().getOrNull()
        assertTrue("Expected Ack, got $result", result is ParsedResponse.Ack)
        assertEquals(0x0B, (result as ParsedResponse.Ack).cmd)
    }

    /** B0B3 has never been observed carrying data; its first-ever payload must log a warning. */
    @Test
    fun onCharacteristicChanged_b0b3_firstDelivery_logsWarningOnce() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val characteristic = characteristicWithUuid("0000b0b3-0000-1000-8000-00805f9b34fb")
        val data = byteArrayOf(0x01, 0x02)

        gattCallback.onCharacteristicChanged(gatt, characteristic, data)
        gattCallback.onCharacteristicChanged(gatt, characteristic, data)

        verify(exactly = 1) {
            Log.w("BleManager", match<String> { it.contains("B0B3 delivered data") })
        }
    }

    /**
     * The deprecated 2-arg overload (API <= 32) sources bytes from `characteristic.value`
     * instead of a `value` parameter; confirm it drives the same UUID -> isAudioChannel routing
     * as the 3-arg overload, so a B0B4 payload that looks like a control packet still lands as
     * exactly one AudioChunk (neither overload calls the other's super, so this is the only way
     * to confirm the deprecated path doesn't silently no-op or double-deliver).
     */
    @Suppress("DEPRECATION")
    @Test
    fun onCharacteristicChanged_deprecatedTwoArgOverload_routesAsAudio() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val characteristic = characteristicWithUuid("0000b0b4-0000-1000-8000-00805f9b34fb")
        val data = ByteArray(244) { 0x00 }
        data[0] = 0xA0.toByte()
        data[1] = 0x0A.toByte()
        data[2] = 0x01
        data[3] = 0x0B
        data[4] = 237.toByte()
        every { characteristic.value } returns data

        gattCallback.onCharacteristicChanged(gatt, characteristic)

        val result = responseChannel().tryReceive().getOrNull()
        assertTrue("Expected AudioChunk, got $result", result is ParsedResponse.AudioChunk)
        assertEquals(null, responseChannel().tryReceive().getOrNull())
    }

    // --- #117: downloadFile must not leave a 0-byte file behind on a zero-byte transfer ------

    /**
     * downloadFile() reads a live GATT connection's response stream driven by real wall-clock
     * timeouts (System.currentTimeMillis(), not virtual test time), so a *silent* zero-byte
     * stall — the real hardware failure mode from #117, where the device acks CMD 0x0B and then
     * sends nothing else — can only be reproduced in a unit test by actually waiting out the
     * real 10s idle timeout. To keep this test fast and non-flaky, we instead drive the same
     * totalBytes==0 exit path via Ack(cmd=0x07), a real, independently-handled branch in
     * downloadFile's loop (`0x07 -> break@outer`) that ends the transfer immediately regardless
     * of bytes received. This is a genuine protocol path already in the code, not a fake — it
     * lets us reach the exact post-loop cleanup logic under test without a 10-second sleep.
     */
    @Test
    fun downloadFile_zeroByteTransfer_deletesTheEmptyFileAndReturnsNull() {
        val tempDir = java.nio.file.Files.createTempDirectory("ble_manager_test").toFile()
        every { context.getExternalFilesDir(null) } returns tempDir

        responseChannel().trySend(ParsedResponse.Ack(0x07))

        val result = runBlocking { manager.downloadFile("zerobyte") {} }

        assertNull(result)
        val expectedFile = File(File(tempDir, "Recordings"), "zerobyte.mp3")
        assertFalse("expected $expectedFile to have been deleted", expectedFile.exists())
    }

    // --- #141: two overlapping collectFileList() calls must not interleave -------------------

    /**
     * Reproduces the measured hardware bug: two overlapping enumerations (e.g. init sequence +
     * a user-triggered refresh) both loop over the single shared responseChannel with nothing
     * serialising them, so the 16 real file entries the FW920 sends back (once per outstanding
     * CMD 0x0A request) get split and duplicated across the two collectFileList() loops instead
     * of each call observing its own complete, duplicate-free 16-file list.
     *
     * Wires a mocked GATT + write characteristic (mirroring sendPacket's early-exit-if-null
     * guard, which the existing downloadFile test relies on) so writeCharacteristic calls can be
     * counted as a proxy for "a collectFileList() call has sent its own CMD 0x0A and entered the
     * response-collection loop". Runs both listFiles() calls on real threads (Dispatchers.Default)
     * so they can genuinely race, then feeds two full 16-entry batches (matching the real
     * hardware: the device answers each outstanding list-files request with the full list) only
     * once the write-count proves which call is actually allowed to be enumerating.
     */
    @Test
    fun collectFileList_twoConcurrentCalls_eachObservesCompleteListNoDuplicatesNoOmissions() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val writeCharMock = mockk<BluetoothGattCharacteristic>(relaxed = true)
        val writeCount = AtomicInteger(0)
        every { gatt.writeCharacteristic(any<BluetoothGattCharacteristic>()) } answers {
            writeCount.incrementAndGet()
            true
        }
        setPrivateField(manager, "bluetoothGatt", gatt)
        setPrivateField(manager, "writeChar", writeCharMock)

        val expectedNames = (1..16).map { "REC%02d".format(it) }

        runBlocking {
            withTimeout(5000) {
                val jobA = async(Dispatchers.Default) { manager.listFiles() }
                val jobB = async(Dispatchers.Default) { manager.listFiles() }

                // Wait for the first call to send its CMD 0x0A.
                while (writeCount.get() < 1) delay(5)

                // The second call must NOT also send its own CMD 0x0A while the first is still
                // enumerating — if it does, both loops are now consuming the same response
                // stream and entries will be split/duplicated between them (the measured bug).
                delay(150)
                assertEquals(
                    "a second collectFileList() call sent its own request while the first " +
                        "call's enumeration was still in flight — responses will now be split " +
                        "between the two loops",
                    1, writeCount.get()
                )

                // Satisfy the first call's enumeration with a complete, duplicate-free 16-entry
                // list (the FW920 answers each outstanding request with the full list).
                expectedNames.forEach { name ->
                    responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
                }
                responseChannel().trySend(ParsedResponse.FileList(null))

                // Only once the first call has released should the second call send its request.
                while (writeCount.get() < 2) delay(5)
                expectedNames.forEach { name ->
                    responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
                }
                responseChannel().trySend(ParsedResponse.FileList(null))

                jobA.await()
                jobB.await()
            }
        }

        val files = manager.bleState.value.files
        assertEquals("expected exactly the 16 real files, no more, no fewer", 16, files.size)
        assertEquals(expectedNames.toSet(), files.map { it.filename }.toSet())
        assertEquals(
            "duplicate filenames present in final file list",
            files.map { it.filename }.distinct().size, files.size
        )
    }

    // --- #141 finding 1: a concurrent status poll must not steal enumeration entries ---------

    /**
     * Reproduces the measured hardware bug: startPoller() fires refreshStatus() every 15s in
     * its own coroutine, guarded only by transferInProgress (which downloadFile sets but
     * collectFileList never does). refreshStatus()'s own awaitResponse(0x05) loop drains the
     * SAME shared responseChannel as an in-flight collectFileList(), and kotlinx.coroutines
     * Channel delivers to waiting receivers in strict FIFO-of-suspension order, so once both
     * coroutines are parked on responseChannel.receive() the channel round-robins entries
     * between them: refreshStatus() silently discards every FileList entry it happens to
     * receive (its expectedCmd is 0x05, not 0x0A), permanently losing roughly half of the
     * enumeration's entries. This models startPoller's own call pattern (an independent
     * scope.launch invoking refreshStatus() while collectFileList() is mid-enumeration), not a
     * synthetic scenario.
     */
    @Test
    fun refreshStatus_duringActiveEnumeration_stealsFileListEntries() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val writeCharMock = mockk<BluetoothGattCharacteristic>(relaxed = true)
        val writeCount = AtomicInteger(0)
        every { gatt.writeCharacteristic(any<BluetoothGattCharacteristic>()) } answers {
            writeCount.incrementAndGet()
            true
        }
        setPrivateField(manager, "bluetoothGatt", gatt)
        setPrivateField(manager, "writeChar", writeCharMock)

        val expectedNames = (1..16).map { "REC%02d".format(it) }

        runBlocking {
            withTimeout(8000) {
                val jobA = async(Dispatchers.Default) { manager.listFiles() }
                while (writeCount.get() < 1) delay(5)

                // Simulate the 15s status poller firing while the enumeration above is still
                // in flight — nothing before the fix stops refreshStatus()'s own
                // awaitResponse(0x05) loop from also draining responseChannel concurrently.
                val jobB = async(Dispatchers.Default) { manager.refreshStatus() }
                // Give both coroutines time to actually park on responseChannel.receive()
                // before any entries are sent, so the FIFO round-robin described above can
                // actually manifest.
                delay(100)

                expectedNames.forEach { name ->
                    responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
                }
                responseChannel().trySend(ParsedResponse.FileList(null))

                jobA.await()
                jobB.await()
            }
        }

        val files = manager.bleState.value.files
        assertEquals(
            "a concurrent refreshStatus() call (modelling the 15s poller) stole file entries " +
                "meant for the in-flight enumeration (#141 finding 1)",
            expectedNames.toSet(), files.map { it.filename }.toSet()
        )
    }

    // --- #141 finding 2: residue from a timed-out enumeration pollutes the NEXT one ----------

    /**
     * Reproduces the measured hardware bug: collectFileList() exits on either the end-of-list
     * sentinel OR a 3s per-item idle timeout. responseChannel is unlimited capacity and nothing
     * drains it between calls, so a call that times out early leaves the device's remaining
     * entries — plus the end-of-list sentinel that eventually arrives — sitting in the channel.
     * The NEXT collectFileList() call (e.g. deleteFile's post-delete confirmation) then drains
     * that stale residue as if it were its own fresh response, instead of the real response to
     * the request it just sent.
     */
    @Test
    fun collectFileList_residueFromTimedOutPriorCall_pollutesTheNextCall() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val writeCharMock = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { gatt.writeCharacteristic(any<BluetoothGattCharacteristic>()) } returns true
        setPrivateField(manager, "bluetoothGatt", gatt)
        setPrivateField(manager, "writeChar", writeCharMock)

        // First enumeration: the device answers only 9 of 16 entries before going quiet. The
        // 3s per-item idle timeout fires and collectFileList() gives up with a partial list.
        val timedOutNames = (1..9).map { "REC%02d".format(it) }
        timedOutNames.forEach { name ->
            responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
        }
        runBlocking { manager.listFiles() }
        assertEquals(9, manager.bleState.value.files.size)

        // The straggler entries + end-of-list sentinel from the FIRST call arrive late and now
        // sit in responseChannel as residue, exactly as #141 finding 2 describes.
        val strayNames = (10..16).map { "REC%02d".format(it) }
        strayNames.forEach { name ->
            responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
        }
        responseChannel().trySend(ParsedResponse.FileList(null))

        // The fresh response to the SECOND call's own CMD 0x0A request.
        val freshNames = (1..5).map { "NEW%02d".format(it) }
        freshNames.forEach { name ->
            responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
        }
        responseChannel().trySend(ParsedResponse.FileList(null))

        runBlocking { manager.listFiles() }

        val files = manager.bleState.value.files
        assertEquals(
            "the second collectFileList() call consumed stale residue from the first, timed-" +
                "out call instead of its own fresh response (#141 finding 2)",
            freshNames.toSet(), files.map { it.filename }.toSet()
        )
    }
}
