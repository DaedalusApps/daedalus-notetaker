package com.daedalus.notes.ble

import android.content.SharedPreferences

data class KnownDevice(
    val mac: String,
    val serial: String,
    val firstSeenAt: Long
)

/** SharedPreferences keys for the known-devices registry and device selection (issue #82). */
object DeviceRegistryPrefs {
    const val KNOWN_DEVICES = "known_devices_json"
    const val SELECTED_MAC = "selected_device_mac"
}

/**
 * Persists devices seen over BLE (MAC + serial) and the user's device selection.
 * Selection is a MAC address, or null/absent for "any device" (today's default behavior).
 *
 * STUB: not yet implemented — see issue #82.
 */
class DeviceRegistry(private val prefs: SharedPreferences) {

    fun knownDevices(): List<KnownDevice> = emptyList()

    /** Upserts by MAC: an existing entry's serial is updated in place, not duplicated. */
    fun upsert(mac: String, serial: String, now: Long = System.currentTimeMillis()) {
        // TODO(#82): persist to prefs
    }

    /** Null means "any device" (default, preserves today's first-responder behavior). */
    fun selectedMac(): String? = null

    fun selectDevice(mac: String?) {
        // TODO(#82): persist to prefs
    }
}
