"""
FW920 BLE GATT protocol constants.

UUIDs below are placeholders populated after running scripts/ble_discover.py.
Update SERVICE_UUID, CONTROL_CHAR_UUID, STATUS_CHAR_UUID, BATTERY_CHAR_UUID
with actual values from discovery output.
"""

SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
STATUS_CHAR_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"
BATTERY_CHAR_UUID = "00002a19-0000-1000-8000-00805f9b34fb"

CMD_RECORD_START = bytes([0x01])
CMD_RECORD_STOP = bytes([0x02])
CMD_RECORD_PAUSE = bytes([0x03])
CMD_STATUS_REQUEST = bytes([0x10])


def parse_status_packet(data: bytes) -> dict:
    battery_pct = data[0] if len(data) > 0 else 0
    storage_raw = int.from_bytes(data[1:3], "big") if len(data) >= 3 else 0
    is_recording = bool(data[3] & 0x01) if len(data) > 3 else False

    return {
        "battery_pct": battery_pct,
        "storage_free_mb": storage_raw,
        "is_recording": is_recording,
    }
