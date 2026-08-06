package com.daedalus.notes.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceRegistryTest {

    private fun prefs() =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    @Test
    fun upsert_sameMacTwice_updatesSerialWithoutDuplicating() {
        val registry = DeviceRegistry(prefs())

        registry.upsert("AA:BB:CC:DD:EE:01", "SERIALOLD")
        registry.upsert("AA:BB:CC:DD:EE:01", "SERIALNEW")

        val devices = registry.knownDevices.value
        assertEquals(1, devices.size)
        assertEquals("SERIALNEW", devices.single().serial)
    }

    @Test
    fun upsert_differentMacs_bothRegistered() {
        val registry = DeviceRegistry(prefs())

        registry.upsert("AA:BB:CC:DD:EE:01", "SERIAL1")
        registry.upsert("AA:BB:CC:DD:EE:02", "SERIAL2")

        val macs = registry.knownDevices.value.map { it.mac }.toSet()
        assertEquals(setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"), macs)
    }

    @Test
    fun selectedMac_defaultsToNull_meaningAnyDevice() {
        val registry = DeviceRegistry(prefs())

        assertNull(registry.selectedMac.value)
    }

    @Test
    fun selectDevice_roundTripsThroughPrefs() {
        val registry = DeviceRegistry(prefs())

        registry.selectDevice("AA:BB:CC:DD:EE:01")
        assertEquals("AA:BB:CC:DD:EE:01", DeviceRegistry(prefs()).selectedMac.value)

        // Selecting "any" (null) clears the persisted choice.
        registry.selectDevice(null)
        assertNull(DeviceRegistry(prefs()).selectedMac.value)
    }

    @Test
    fun upsert_persistsAcrossNewRegistryInstance() {
        val registry = DeviceRegistry(prefs())

        registry.upsert("AA:BB:CC:DD:EE:01", "SERIAL1")

        val reloaded = DeviceRegistry(prefs())
        assertEquals(listOf("SERIAL1"), reloaded.knownDevices.value.map { it.serial })
    }
}
