package com.daedalus.notes.ble

/** The advertised name the FW920 scans for when no specific device is selected. */
const val FW920_NAME = "FW920"

/** The MAC the next scan should target, or null to fall back to the FW920 name filter. */
fun scanTargetMac(selectedMac: String?): String? = selectedMac?.takeUnless { it.isBlank() }

/** Message shown when a scan times out without finding a device (issue #82). */
fun scanTimeoutMessage(targetedMac: Boolean): String =
    if (targetedMac) "Selected device not found — pick Any device to reconnect" else "No FW920 found"
