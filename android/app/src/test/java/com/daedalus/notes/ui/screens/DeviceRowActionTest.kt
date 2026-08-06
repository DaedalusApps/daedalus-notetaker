package com.daedalus.notes.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRowActionTest {

    @Test
    fun deviceRowAction_selected_connected_isSelected() {
        assertEquals(DeviceRowAction.SELECTED, deviceRowAction(isSelected = true, isConnected = true))
    }

    @Test
    fun deviceRowAction_selected_notConnected_isSelected() {
        assertEquals(DeviceRowAction.SELECTED, deviceRowAction(isSelected = true, isConnected = false))
    }

    @Test
    fun deviceRowAction_notSelected_notConnected_isConnect() {
        assertEquals(DeviceRowAction.CONNECT, deviceRowAction(isSelected = false, isConnected = false))
    }

    @Test
    fun deviceRowAction_notSelected_connected_isNone() {
        assertEquals(DeviceRowAction.NONE, deviceRowAction(isSelected = false, isConnected = true))
    }
}
