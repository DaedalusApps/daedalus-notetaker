package com.daedalus.notes.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanTargetingTest {

    @Test
    fun scanTargetFor_selectedMac_targetsThatAddress() {
        val target = scanTargetFor("AA:BB:CC:DD:EE:01")

        assertEquals(ScanTarget.ByMac("AA:BB:CC:DD:EE:01"), target)
    }

    @Test
    fun scanTargetFor_noSelection_targetsByFw920Name() {
        assertEquals(ScanTarget.ByName, scanTargetFor(null))
    }

    @Test
    fun scanTargetFor_blankSelection_targetsByFw920Name() {
        assertEquals(ScanTarget.ByName, scanTargetFor(""))
    }
}
