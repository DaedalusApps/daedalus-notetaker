"""
FW920 BLE GATT protocol constants — extracted from nRF Connect live capture.

Confirmed via nRF Connect log (2026-05-06): device connects on LE transport,
upgrades to LE 2M PHY, then exposes this GATT table.

Note: E606E15D family from earlier libapp.so analysis is for a DIFFERENT
DOWAY product. The FW920 uses the FFD0 / B0B0 / C0C0 family below.

Disconnect behaviour: device terminates connection after ~5 seconds if the
central does not subscribe to notifications. Subscribe within 3 seconds.
"""

# ---------------------------------------------------------------------------
# Primary control service (BLE UART / transparent serial)
# ---------------------------------------------------------------------------
SERVICE_UUID       = "0000ffd0-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID    = "0000ffd1-0000-1000-8000-00805f9b34fb"  # W, WNR — send commands
NOTIFY_CHAR_UUID   = "0000ffd2-0000-1000-8000-00805f9b34fb"  # N   — device → central
BIDIR_CHAR_UUID    = "0000ffd3-0000-1000-8000-00805f9b34fb"  # N + W — bidirectional

# Backwards-compatible aliases
CONTROL_CHAR_UUID  = WRITE_CHAR_UUID
STATUS_CHAR_UUID   = NOTIFY_CHAR_UUID

# ---------------------------------------------------------------------------
# Secondary services (OTA / config / extended)
# ---------------------------------------------------------------------------
B0B0_SERVICE_UUID  = "0000b0b0-0000-1000-8000-00805f9b34fb"
B0B1_CHAR_UUID     = "0000b0b1-0000-1000-8000-00805f9b34fb"  # WNR
B0B2_CHAR_UUID     = "0000b0b2-0000-1000-8000-00805f9b34fb"  # N
B0B3_CHAR_UUID     = "0000b0b3-0000-1000-8000-00805f9b34fb"  # N
B0B4_CHAR_UUID     = "0000b0b4-0000-1000-8000-00805f9b34fb"  # N

C0C0_SERVICE_UUID  = "0000c0c0-0000-1000-8000-00805f9b34fb"
C0C1_CHAR_UUID     = "0000c0c1-0000-1000-8000-00805f9b34fb"  # WNR
C0C2_CHAR_UUID     = "0000c0c2-0000-1000-8000-00805f9b34fb"  # N

E49A_SERVICE_UUID  = "e49a3001-f69a-11e8-8eb2-f2801f1b9fd1"
E49A_WRITE_UUID    = "e49a3002-f69a-11e8-8eb2-f2801f1b9fd1"  # WNR
E49A_NOTIFY_UUID   = "e49a3003-f69a-11e8-8eb2-f2801f1b9fd1"  # N

# Standard
BATTERY_CHAR_UUID  = "00002a19-0000-1000-8000-00805f9b34fb"
CCCD_UUID          = "00002902-0000-1000-8000-00805f9b34fb"

# ---------------------------------------------------------------------------
# Connection parameters (from nRF Connect capture)
# ---------------------------------------------------------------------------
CONN_INTERVAL_MS   = 30.0   # device requests 30ms after initial 7.5ms
CONN_PHY           = "2M"   # device upgrades to LE 2M PHY immediately
KEEPALIVE_WINDOW_S = 5      # device disconnects if nothing happens in 5s

# ---------------------------------------------------------------------------
# Protocol identity (from DOWAY libapp.so static analysis)
# ---------------------------------------------------------------------------
# Internal name: xlx_link (from Dart source path: dowayProtocol/bluetooth/xlx_link/)
# Chip protocol: cmd_2837 (ATS2837 audio DSP, controlled via ESP32-C3 BLE bridge)
# Alt variant:   cmd_3085s (different hardware SKU, not FW920)
# Debug markers: "xlx_2837 start", "xlx_3085s"
#
# Connection behaviour:
# - Device sends a SyncTimeRequest immediately after connect
# - If central does not respond within ~5 seconds, device disconnects (HCI 0x13)
# - Response packet header seen in debug log: 0x0905
# - Exact SyncTimeRequest byte format: TBD (need HCI snoop capture to confirm)
#
# Linux note: BlueZ routes to BR/EDR because ESP32-C3 firmware omits the
# 0x04 "BR/EDR Not Supported" advertising flag. Android specifies
# TRANSPORT_LE explicitly (connectGatt(..., TRANSPORT_LE, ...)) and works.
# Fix requires root: sudo btmgmt bredr off, or /etc/bluetooth/main.conf
# ControllerMode = le. Android app (Phase 2) has no issue.
# ---------------------------------------------------------------------------
CMD_RECORD_START   = bytes([0x01])   # placeholder — confirm via HCI snoop
CMD_RECORD_STOP    = bytes([0x02])
CMD_RECORD_PAUSE   = bytes([0x03])
CMD_STATUS_REQUEST = bytes([0x10])


def parse_status_packet(data: bytes) -> dict:
    """Raw notify parser — format not yet reverse-engineered."""
    return {
        "raw_hex": data.hex(),
        "length": len(data),
        "byte0": data[0] if len(data) > 0 else None,
    }
