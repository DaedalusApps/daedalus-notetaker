package com.daedalus.notes.ui.screens

/** The action a device row in Settings > Devices should render (issue #87). */
enum class DeviceRowAction { SELECTED, CONNECT, NONE }

/**
 * Decides what a device row should show:
 * - selected (pinned): "Selected" badge, regardless of connection state.
 * - not selected, not connected: "Connect" button.
 * - not selected, connected (auto mode picked it): no action.
 */
fun deviceRowAction(isSelected: Boolean, isConnected: Boolean): DeviceRowAction = DeviceRowAction.CONNECT
