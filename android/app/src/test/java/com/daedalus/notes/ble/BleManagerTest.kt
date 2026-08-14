package com.daedalus.notes.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
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

    /**
     * Wires a mocked GATT + write characteristic onto [target] and returns an AtomicInteger that
     * counts every writeCharacteristic call, so tests can gate response-feeding on "a specific
     * request has actually gone out" instead of a bare delay (see
     * collectFileList_twoConcurrentCalls's KDoc for why write-count gating is required for these
     * races to be deterministic).
     */
    private fun wireGattWithWriteCounter(target: BleManager = manager): AtomicInteger {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val writeCharMock = mockk<BluetoothGattCharacteristic>(relaxed = true)
        val writeCount = AtomicInteger(0)
        every { gatt.writeCharacteristic(any<BluetoothGattCharacteristic>()) } answers {
            writeCount.incrementAndGet()
            true
        }
        setPrivateField(target, "bluetoothGatt", gatt)
        setPrivateField(target, "writeChar", writeCharMock)
        return writeCount
    }

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

    // --- #148: a stale (superseded) GATT callback reporting STATE_CONNECTED must be torn down ---
    // --- (that gatt only — the current connection must be left completely untouched) ------------

    @Test
    fun onConnectionStateChange_staleGattReportsConnected_disconnectsAndClosesThatGattOnly() {
        val currentGatt = mockk<BluetoothGatt>(relaxed = true)
        setPrivateField(manager, "bluetoothGatt", currentGatt)
        val stateBefore = manager.bleState.value.connectionState

        val staleGatt = mockk<BluetoothGatt>(relaxed = true)
        gattCallback.onConnectionStateChange(
            staleGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED
        )

        verify { staleGatt.disconnect() }
        verify { staleGatt.close() }
        verify(exactly = 0) { currentGatt.disconnect() }
        verify(exactly = 0) { currentGatt.close() }
        assertEquals(currentGatt, privateField(manager, "bluetoothGatt"))
        assertEquals(stateBefore, manager.bleState.value.connectionState)
    }

    // --- #151 (closed as unsubstantiated, applied here since we're already editing this
    // --- callback): a failed-status STATE_CONNECTED must not request an MTU, and — per the
    // --- file's own onScanFailed precedent — must go DISCONNECTED (not ERROR) so the
    // --- auto-connect LaunchedEffect can retry cleanly, with the stale gatt closed and cleared
    // --- so it isn't leaked, and an errorMessage naming the status for diagnosability. ----------

    @Test
    fun onConnectionStateChange_currentConnectionFailedStatus_goesDisconnectedAndClosesGatt() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        setPrivateField(manager, "bluetoothGatt", gatt)

        gattCallback.onConnectionStateChange(gatt, /* status = */ 133, BluetoothProfile.STATE_CONNECTED)

        verify(exactly = 0) { gatt.requestMtu(any()) }
        verify { gatt.close() }
        assertNull(privateField(manager, "bluetoothGatt"))
        assertEquals(ConnectionState.DISCONNECTED, manager.bleState.value.connectionState)
        assertTrue(manager.bleState.value.errorMessage.contains("133"))
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
        val writeCount = wireGattWithWriteCounter()

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
                // A single point-in-time check after a fixed sleep (e.g. delay(150) then check
                // once) is a wall-clock heuristic: on a saturated CI box the second coroutine
                // might simply not have been scheduled yet within that window, giving a false
                // pass. Poll continuously over a longer window instead — any transition to 2
                // fails immediately, and a full 500ms of the second call staying quiescent is
                // far stronger evidence than one sample at 150ms.
                repeat(20) {
                    assertEquals(
                        "a second collectFileList() call sent its own request while the first " +
                            "call's enumeration was still in flight — responses will now be " +
                            "split between the two loops",
                        1, writeCount.get()
                    )
                    delay(25)
                }

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

    // --- #141 finding 5: the mutex must be per-instance, not shared across instances --------

    /**
     * Moving fileListMutex into a companion object would over-synchronise across BleManager
     * instances — a real bug during a device swap, where a fresh BleManager for the new device
     * would block on a stale enumeration left in flight by the OLD instance. A single-instance
     * test (like the one above) cannot distinguish a per-instance mutex from a static one, since
     * it never has two instances to compare. This test does: two independent BleManager
     * instances, each wired to its own mocked GATT, must both be able to send their own CMD 0x0A
     * and start enumerating without waiting on each other.
     *
     * A static mutex does NOT make this test hang until the outer withTimeout budget: instance
     * 1's enumeration is left deliberately unsatisfied, but collectFileList()'s own 3s per-item
     * idle timeout fires, releases the lock, and instance 2 proceeds well inside a generous
     * outer timeout — so the test would stay green under that mutation. The outer budget must
     * therefore be tighter than that 3s escape hatch (measured: unmutated path completes in
     * ~0.07s, so 1200ms leaves ample headroom without flaking) so that the mutation is actually
     * caught by a timeout failure instead of slipping through underneath it.
     */
    @Test
    fun collectFileList_mutexIsPerInstance_secondManagerIsNotBlockedByFirst() {
        val prefs2 = mockk<SharedPreferences>(relaxed = true)
        val context2 = mockk<Context>(relaxed = true)
        every { context2.getSharedPreferences(any(), any()) } returns prefs2
        every { prefs2.getString(any(), any()) } returns null
        val manager2 = BleManager(context2)

        val writeCount1 = AtomicInteger(0)
        val writeCount2 = AtomicInteger(0)
        val gatt1 = mockk<BluetoothGatt>(relaxed = true)
        val gatt2 = mockk<BluetoothGatt>(relaxed = true)
        val writeChar1 = mockk<BluetoothGattCharacteristic>(relaxed = true)
        val writeChar2 = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { gatt1.writeCharacteristic(any<BluetoothGattCharacteristic>()) } answers {
            writeCount1.incrementAndGet(); true
        }
        every { gatt2.writeCharacteristic(any<BluetoothGattCharacteristic>()) } answers {
            writeCount2.incrementAndGet(); true
        }
        setPrivateField(manager, "bluetoothGatt", gatt1)
        setPrivateField(manager, "writeChar", writeChar1)
        setPrivateField(manager2, "bluetoothGatt", gatt2)
        setPrivateField(manager2, "writeChar", writeChar2)

        @Suppress("UNCHECKED_CAST")
        fun channelOf(target: BleManager) = privateField(target, "responseChannel") as Channel<ParsedResponse>

        runBlocking {
            // Tighter than collectFileList's 3s per-item idle timeout escape hatch: under a
            // static/shared mutex, instance 1's still-open enumeration below would eventually
            // time out and release the lock, letting instance 2 proceed inside a 5000ms budget
            // and passing incorrectly. A 1200ms budget forces a genuine failure instead.
            withTimeout(1200) {
                // Instance 1 starts enumerating and is deliberately left unsatisfied.
                val job1 = async(Dispatchers.Default) { manager.listFiles() }
                while (writeCount1.get() < 1) delay(5)

                // Instance 2 must be able to start its own enumeration right away.
                val job2 = async(Dispatchers.Default) { manager2.listFiles() }
                while (writeCount2.get() < 1) delay(5)

                // Both did — release both so the coroutines complete cleanly.
                channelOf(manager).trySend(ParsedResponse.FileList(null))
                channelOf(manager2).trySend(ParsedResponse.FileList(null))
                job1.await()
                job2.await()
            }
        }

        assertEquals(1, writeCount1.get())
        assertEquals(1, writeCount2.get())
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
        val writeCount = wireGattWithWriteCounter()

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
        val writeCount = wireGattWithWriteCounter()

        runBlocking {
            withTimeout(8000) {
                // First enumeration: the device answers only 9 of 16 entries before going
                // quiet. The 3s per-item idle timeout fires and collectFileList() gives up with
                // a partial list. The entries are sent only after the request itself goes out,
                // otherwise the fix's own drain-before-send step (correctly) discards them as
                // pre-existing residue before this scenario is even set up.
                val timedOutNames = (1..9).map { "REC%02d".format(it) }
                val jobA = async(Dispatchers.Default) { manager.listFiles() }
                while (writeCount.get() < 1) delay(5)
                timedOutNames.forEach { name ->
                    responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
                }
                jobA.await()
            }
        }
        assertEquals(9, manager.bleState.value.files.size)

        // The straggler entries + end-of-list sentinel from the FIRST call arrive late and now
        // sit in responseChannel as residue, exactly as #141 finding 2 describes.
        val strayNames = (10..16).map { "REC%02d".format(it) }
        strayNames.forEach { name ->
            responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
        }
        responseChannel().trySend(ParsedResponse.FileList(null))

        // The fresh response to the SECOND call's own CMD 0x0A request — again sent only after
        // that call's own request goes out (writeCount reaching 2), so it lands after this
        // call's drain-then-send step rather than being drained away itself.
        val freshNames = (1..5).map { "NEW%02d".format(it) }
        runBlocking {
            withTimeout(8000) {
                val jobB = async(Dispatchers.Default) { manager.listFiles() }
                while (writeCount.get() < 2) delay(5)
                freshNames.forEach { name ->
                    responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
                }
                responseChannel().trySend(ParsedResponse.FileList(null))
                jobB.await()
            }
        }

        val files = manager.bleState.value.files
        assertEquals(
            "the second collectFileList() call consumed stale residue from the first, timed-" +
                "out call instead of its own fresh response (#141 finding 2)",
            freshNames.toSet(), files.map { it.filename }.toSet()
        )
    }

    // --- #144: deleteFile's own 0x0D exchanges are unguarded --------------------------------

    /**
     * Reproduces the measured hardware bug (#144): deleteFile()'s two sendAndAwait(...,
     * expectedCmd = 0x0D) exchanges (stage then commit) are not covered by any mutex — only its
     * trailing collectFileList() call is. The 15s status poller's refreshStatus() can acquire
     * fileListMutex uncontended while deleteFile is mid-exchange, and its own
     * awaitResponse(0x05) loop drains the same shared responseChannel, silently discarding the
     * Unknown(cmd=0x0D) commit ack meant for deleteFile. deleteFile then times out waiting for
     * the commit ack, returns false, and the UI reports "Failed to delete" for a file the device
     * actually deleted.
     */
    @Test
    fun deleteFile_duringConcurrentRefreshStatus_commitAckIsNotStolen() {
        val writeCount = wireGattWithWriteCounter()

        var deleteResult = false
        runBlocking {
            withTimeout(6000) {
                val jobDelete = async(Dispatchers.Default) { manager.deleteFile("REC01") }
                // Wait for deleteFile's first write (the stage 0x0D request).
                while (writeCount.get() < 1) delay(5)

                // Simulate the 15s status poller firing while deleteFile is still mid-exchange —
                // nothing before the fix stops refreshStatus()'s own awaitResponse(0x05) loop
                // from also draining responseChannel concurrently.
                val jobRefresh = async(Dispatchers.Default) { manager.refreshStatus() }
                // Give both coroutines time to actually park on responseChannel.receive() before
                // any entries are sent, so the FIFO round-robin theft can actually manifest.
                delay(100)

                responseChannel().trySend(ParsedResponse.Unknown(0x0D, byteArrayOf(0)))  // stage ack
                while (writeCount.get() < 2 && !jobDelete.isCompleted) delay(5)
                delay(100)
                responseChannel().trySend(ParsedResponse.Unknown(0x0D, byteArrayOf(1)))  // commit ack

                // If the commit ack was consumed correctly, deleteFile proceeds to its
                // post-delete enumeration (a third write). If it was instead stolen, deleteFile
                // bails out early without enumerating — wait for whichever happens first.
                while (writeCount.get() < 3 && !jobDelete.isCompleted) delay(5)
                if (writeCount.get() >= 3) {
                    listOf("REC02", "REC03").forEach { name ->
                        responseChannel().trySend(ParsedResponse.FileList(FileEntry(name, 1024L)))
                    }
                    responseChannel().trySend(ParsedResponse.FileList(null))
                }

                // Let refreshStatus complete too, so it doesn't leak into a later test. Gated on
                // its own write (the 4th overall: stage, commit, deleteFile's post-delete
                // list-files, then refreshStatus's status request) rather than sent unconditionally
                // — refreshStatus is now queued behind deleteFile's linkMutex hold, and withLink
                // drains residue at its own entry, so a Status sent before refreshStatus's write
                // goes out would just be drained away as stale residue, forcing this test to dead-
                // wait out refreshStatus's own 3s sendAndAwait timeout instead.
                while (writeCount.get() < 4 && !jobRefresh.isCompleted) delay(5)
                responseChannel().trySend(
                    ParsedResponse.Status(
                        cmd = 0x05,
                        status = DeviceStatus(50, 1000L, 2000L, false, "fw")
                    )
                )

                deleteResult = jobDelete.await()
                jobRefresh.await()
            }
        }

        assertTrue(
            "deleteFile's commit ack must not be stolen by a concurrent refreshStatus() (#144)",
            deleteResult
        )
    }

    // --- #144: downloadFile's audio stream is unguarded --------------------------------------

    /**
     * Reproduces the measured hardware bug (#144): downloadFile() reads responseChannel.receive()
     * directly with no serialisation. A concurrent collectFileList() (via listFiles()) — whose
     * awaitResponse(0x0A) discards any AudioChunk it happens to receive — can steal chunks meant
     * for an in-flight download, silently corrupting the downloaded file while it still reports
     * success.
     */
    @Test
    fun downloadFile_duringConcurrentListFiles_audioChunksAreNotStolen() {
        val tempDir = java.nio.file.Files.createTempDirectory("ble_manager_test2").toFile()
        every { context.getExternalFilesDir(null) } returns tempDir

        val writeCount = wireGattWithWriteCounter()

        val chunks = (0 until 20).map { i -> ByteArray(10) { ((i * 10) + it).toByte() } }
        val expectedBytes = chunks.reduce { acc, c -> acc + c }

        var resultFile: File? = null
        runBlocking {
            // Generous: on unfixed code downloadFile can legitimately run out its real 10s idle
            // timeout before giving up, since its own internal timeout is real wall-clock.
            withTimeout(15000) {
                val jobDownload = async(Dispatchers.Default) { manager.downloadFile("REC01") {} }
                // Wait for downloadFile's CMD 0x0B write.
                while (writeCount.get() < 1) delay(5)

                val jobList = async(Dispatchers.Default) { manager.listFiles() }
                // Give both coroutines time to actually park on responseChannel.receive().
                delay(100)

                responseChannel().trySend(ParsedResponse.Ack(0x0B))  // ready
                chunks.forEach { c -> responseChannel().trySend(ParsedResponse.AudioChunk(c)) }
                responseChannel().trySend(ParsedResponse.Ack(0x0B))  // EOF

                while (writeCount.get() < 2 && !jobList.isCompleted) delay(5)
                if (writeCount.get() >= 2) {
                    responseChannel().trySend(ParsedResponse.FileList(FileEntry("REC02", 1024L)))
                    responseChannel().trySend(ParsedResponse.FileList(null))
                }

                resultFile = jobDownload.await()
                jobList.await()
            }
        }

        assertTrue("downloadFile should have produced a file", resultFile != null)
        val actualBytes = resultFile!!.readBytes()
        assertEquals(
            "downloaded file content must exactly match the concatenation of all audio chunks " +
                "— a concurrent listFiles() must not steal any AudioChunk (#144)",
            expectedBytes.size, actualBytes.size
        )
        assertTrue(expectedBytes.contentEquals(actualBytes))
    }

    // --- #144 review round finding 1: deleteFile misreads a late stage ack as the commit ack --

    /**
     * If deleteFile's stage sendAndAwait(0x0D) times out (device latency > 3s), the OLD code
     * still sent the commit packet regardless. A stage ack that then arrives late — after commit
     * has been sent but before its own awaitResponse(0x0D) reads it — gets consumed as if it were
     * the COMMIT response instead: its payload=[0] ("staged, not committed") reads as a failed
     * commit, so deleteFile reports false even though the device may go on to actually delete the
     * file once the (never-sent) commit... except in this exact scenario the device never even
     * received a commit request, since deleteFile only sent ONE packet (the stage) and then misread
     * its own late stage ack as commit's answer. The fix bails out on a null stage instead of
     * racing a stray late response — provable here via write count: a timed-out stage exchange
     * must never cause a second (commit) packet to go out.
     */
    @Test
    fun deleteFile_stageTimesOutThenArrivesLate_doesNotMisreadAsCommitAckAndSendsOnlyOnePacket() {
        val writeCount = wireGattWithWriteCounter()

        var deleteResult = true  // default true so a bug that silently succeeds isn't masked
        runBlocking {
            withTimeout(8000) {
                val jobDelete = async(Dispatchers.Default) { manager.deleteFile("REC01") }
                while (writeCount.get() < 1) delay(5)

                // Let the stage sendAndAwait's own 3000ms timeout actually fire in real time
                // before its ack is delivered — reproduces a device with >3s stage latency.
                delay(3200)
                responseChannel().trySend(ParsedResponse.Unknown(0x0D, byteArrayOf(0)))  // late stage ack

                deleteResult = jobDelete.await()
            }
        }

        assertFalse(
            "deleteFile must not report success from a late stage ack it never correctly " +
                "attributed (#144 review finding 1)",
            deleteResult
        )
        assertEquals(
            "a timed-out stage exchange must bail out instead of sending a commit packet — " +
                "otherwise the late stage ack sitting in the channel gets misread as the commit " +
                "response (#144 review finding 1)",
            1, writeCount.get()
        )
    }

    // --- #144 review round finding 2: collectFileListLocked's own loop needs its own drain ----

    /**
     * withLink's drain only runs once, at the OUTER gate entry. probeDeleteCmds and
     * probeUploadCmds call collectFileListLocked() in a LOOP under a single withLink hold, so
     * without a drain inside collectFileListLocked itself, one iteration's enumeration timing out
     * with stragglers still arriving would have them consumed by the NEXT iteration's own
     * collectFileListLocked call as if they were its answer — the same cross-call corruption
     * #141/#144's drain already prevents at the outer boundary, just one level down.
     *
     * Drives this through probeDeleteCmds (a real looping call site), using write-count gating
     * throughout so the residue is sent only once it is guaranteed to be genuine stragglers (i.e.
     * after the first iteration's own probe+enumeration are fully done) and the fresh answer is
     * sent only once the second iteration's own list-files request has actually gone out.
     */
    @Test
    fun probeDeleteCmds_strandedEnumerationResidue_doesNotCorruptNextIterationsEnumeration() {
        val writeCount = wireGattWithWriteCounter()
        val cleanName = "TARGETFILE"

        runBlocking {
            withTimeout(15000) {
                val jobProbe = async(Dispatchers.Default) { manager.probeDeleteCmds(cleanName) }

                // Iteration 1 (cmd 0x0D): answer its own probe-response await (expectedCmd=0x0D)
                // immediately with a plain ack, so the test isn't forced to wait out its 1500ms
                // timeout for no reason — awaitResponse's linear scan would otherwise discard any
                // FileList sent here anyway, since FileList never matches a non-0x0A expectedCmd.
                while (writeCount.get() < 1) delay(5)
                responseChannel().trySend(ParsedResponse.Unknown(0x0D, byteArrayOf()))

                // Iteration 1's collectFileListLocked write (#2).
                while (writeCount.get() < 2) delay(5)
                // Answer with two entries, INCLUDING cleanName, so iteration 1 does not report
                // "gone" and the loop proceeds to iteration 2 — but withhold the end-of-list
                // sentinel so this enumeration gives up on its own hard-coded 3000ms idle timeout
                // instead of completing cleanly, leaving genuine stragglers unconsumed.
                responseChannel().trySend(ParsedResponse.FileList(FileEntry(cleanName, 1024L)))
                responseChannel().trySend(ParsedResponse.FileList(FileEntry("OTHER", 1024L)))

                // Iteration 2's probe write (#3) only happens once iteration 1's
                // collectFileListLocked has genuinely given up on its 3000ms idle timeout and
                // probeDeleteCmds has moved on — so by the time we observe it, iteration 1 is
                // fully done.
                while (writeCount.get() < 3) delay(5)
                // Answer iteration 2's own probe-response await (expectedCmd=0x0E) immediately
                // too, for the same reason as iteration 1 above — and critically, this makes what
                // follows deterministic: awaitResponse(0x0E) resumes and returns synchronously
                // relative to probeDeleteCmds' own subsequent 300ms delay before it calls
                // collectFileListLocked again, so anything sent right here — the straggler entry
                // and end-of-list sentinel modelling iteration 1's late, never-consumed
                // response — is guaranteed to already be sitting in responseChannel well before
                // collectFileListLocked's own drain (or lack thereof) runs, with no reliance on
                // real-time margins.
                responseChannel().trySend(ParsedResponse.Unknown(0x0E, byteArrayOf()))
                val strayName = "STALE_STRAGGLER"
                responseChannel().trySend(ParsedResponse.FileList(FileEntry(strayName, 1024L)))
                responseChannel().trySend(ParsedResponse.FileList(null))

                // Iteration 2's own collectFileListLocked write (#4) — only once this has
                // actually gone out do we send the FRESH answer, so a fix that correctly drains
                // the stragglers above waits for and receives this, not iteration 1's leftovers.
                // Deliberately excludes cleanName so iteration 2 reports "gone" and
                // probeDeleteCmds returns immediately instead of looping further.
                while (writeCount.get() < 4) delay(5)
                val freshName = "FRESH_ANSWER"
                responseChannel().trySend(ParsedResponse.FileList(FileEntry(freshName, 1024L)))
                responseChannel().trySend(ParsedResponse.FileList(null))

                jobProbe.await()
            }
        }

        val filenames = manager.bleState.value.files.map { it.filename }.toSet()
        assertEquals(
            "iteration 2's enumeration must reflect its OWN fresh response, not iteration 1's " +
                "stranded stragglers (#144 review finding 2)",
            setOf("FRESH_ANSWER"), filenames
        )
    }
}
