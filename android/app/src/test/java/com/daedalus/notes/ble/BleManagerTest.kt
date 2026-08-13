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
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

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
}
