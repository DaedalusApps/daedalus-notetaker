package com.daedalus.notes.ble

/** The advertised name the FW920 scans for when no specific device is selected. */
const val FW920_NAME = "FW920"

/** The device the next scan should target — a specific MAC, or any FW920 by advertised name. */
sealed class ScanTarget {
    data class ByMac(val mac: String) : ScanTarget()
    object ByName : ScanTarget()
}

fun scanTargetFor(selectedMac: String?): ScanTarget =
    if (selectedMac.isNullOrBlank()) ScanTarget.ByName else ScanTarget.ByMac(selectedMac)
