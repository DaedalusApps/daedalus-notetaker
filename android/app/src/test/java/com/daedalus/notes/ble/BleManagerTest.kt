package com.daedalus.notes.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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
}
