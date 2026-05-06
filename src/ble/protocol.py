"""
FW920 BLE GATT protocol constants — extracted from DOWAY APK static analysis.

Reverse-engineered from libapp.so (DOWAY 3.6.5 Flutter Dart binary):
- Service UUID, write characteristic, and notify characteristic identified
  via 128-bit UUID strings in the binary
- Packet format hints (cmd, deviceId, payload, protocol, startFrame, recordSt)
  observed in debug log format strings
- Exact command bytes are not yet known; populate after capturing a live
  exchange or further dynamic analysis
"""

# ---------------------------------------------------------------------------
# GATT UUIDs (from DOWAY static analysis)
# ---------------------------------------------------------------------------
SERVICE_UUID = "e606e15d-da3d-6f45-5978-a7a5b8cea2c0"
WRITE_CHAR_UUID = "e606e15e-da3d-6f45-5978-a7a5b8cea2c0"
NOTIFY_CHAR_UUID = "e606e15f-da3d-6f45-5978-a7a5b8cea2c0"

# Backwards-compatible aliases (older code uses these names)
CONTROL_CHAR_UUID = WRITE_CHAR_UUID
STATUS_CHAR_UUID = NOTIFY_CHAR_UUID
BATTERY_CHAR_UUID = "00002a19-0000-1000-8000-00805f9b34fb"  # standard BLE battery service

# Standard BLE descriptor: Client Characteristic Configuration (CCCD)
CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

# Alternate device families seen in DOWAY (probably for related products)
ALT_FAMILY_1_UUIDS = (
    "0011200a-2233-4455-6677-889912345678",
    "0011201a-2233-4455-6677-889912345678",
    "0011202a-2233-4455-6677-889912345678",
)
ALT_FAMILY_2_UUIDS = (
    "e49a25e0-f69a-11e8-8eb2-f2801f1b9fd1",
    "e49a25f8-f69a-11e8-8eb2-f2801f1b9fd1",
    "e49a28e1-f69a-11e8-8eb2-f2801f1b9fd1",
)


# ---------------------------------------------------------------------------
# Command bytes (PLACEHOLDERS — confirm via live capture)
# ---------------------------------------------------------------------------
CMD_RECORD_START = bytes([0x01])
CMD_RECORD_STOP = bytes([0x02])
CMD_RECORD_PAUSE = bytes([0x03])
CMD_STATUS_REQUEST = bytes([0x10])


def parse_status_packet(data: bytes) -> dict:
    """Tentative parser based on RE'd field names: cmd, deviceId, recordSt, startFrame.

    Real packet format unknown until we capture live notifications. Returns
    raw hex alongside best-guess fields so callers can see both.
    """
    return {
        "raw_hex": data.hex(),
        "length": len(data),
        "battery_pct": data[0] if len(data) > 0 else 0,
        "storage_free_mb": int.from_bytes(data[1:3], "big") if len(data) >= 3 else 0,
        "is_recording": bool(data[3] & 0x01) if len(data) > 3 else False,
    }
