package com.daedalus.notes.ble

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

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
 * Prefs are read once at construction into in-memory state; upsert()/selectDevice() update
 * that state immediately and write through to prefs asynchronously, so callers (including the
 * BLE GATT callback thread) never block on a re-parse of the stored JSON.
 */
class DeviceRegistry(private val prefs: SharedPreferences) {

    private val _knownDevices = MutableStateFlow(loadKnownDevices())
    val knownDevices: StateFlow<List<KnownDevice>> = _knownDevices.asStateFlow()

    private val _selectedMac = MutableStateFlow(prefs.getString(DeviceRegistryPrefs.SELECTED_MAC, null))
    val selectedMac: StateFlow<String?> = _selectedMac.asStateFlow()

    private fun loadKnownDevices(): List<KnownDevice> {
        val json = prefs.getString(DeviceRegistryPrefs.KNOWN_DEVICES, null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            KnownDevice(
                mac = obj.getString("mac"),
                serial = obj.getString("serial"),
                firstSeenAt = obj.getLong("firstSeenAt")
            )
        }
    }

    /** Upserts by MAC: an existing entry's serial is updated in place, not duplicated. */
    fun upsert(mac: String, serial: String, now: Long = System.currentTimeMillis()) {
        val existing = _knownDevices.value
        val current = existing.firstOrNull { it.mac == mac }
        if (current?.serial == serial) return  // no change — skip the rewrite
        val updated = existing.filterNot { it.mac == mac } + KnownDevice(mac, serial, current?.firstSeenAt ?: now)
        _knownDevices.value = updated

        val arr = JSONArray()
        updated.forEach { device ->
            arr.put(JSONObject().apply {
                put("mac", device.mac)
                put("serial", device.serial)
                put("firstSeenAt", device.firstSeenAt)
            })
        }
        prefs.edit().putString(DeviceRegistryPrefs.KNOWN_DEVICES, arr.toString()).apply()
    }

    fun selectDevice(mac: String?) {
        _selectedMac.value = mac
        prefs.edit().apply {
            if (mac == null) remove(DeviceRegistryPrefs.SELECTED_MAC)
            else putString(DeviceRegistryPrefs.SELECTED_MAC, mac)
        }.apply()
    }
}
