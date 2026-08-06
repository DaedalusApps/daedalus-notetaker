package com.daedalus.notes.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanTargetingTest {

    @Test
    fun scanTargetMac_selectedMac_returnsThatAddress() {
        assertEquals("AA:BB:CC:DD:EE:01", scanTargetMac("AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun scanTargetMac_noSelection_returnsNull() {
        assertNull(scanTargetMac(null))
    }

    @Test
    fun scanTargetMac_blankSelection_returnsNull() {
        assertNull(scanTargetMac(""))
    }
}
