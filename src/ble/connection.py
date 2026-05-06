"""Async BLE connection manager for the FW920 recorder."""

import asyncio

from bleak import BleakClient
from bleak.backends.device import BLEDevice
from bleak.exc import BleakError

from src.ble.protocol import (
    CMD_RECORD_PAUSE,
    CMD_RECORD_START,
    CMD_RECORD_STOP,
    CMD_STATUS_REQUEST,
    CONTROL_CHAR_UUID,
    STATUS_CHAR_UUID,
    parse_status_packet,
)

_RECONNECT_ATTEMPTS = 3
_STATUS_WAIT = 0.5


class BleConnection:
    def __init__(self, device: BLEDevice) -> None:
        self._device = device
        self._client: BleakClient | None = None
        self.last_status: dict = {}

    def _on_notify(self, _sender, data: bytearray) -> None:
        self.last_status = parse_status_packet(bytes(data))

    async def connect(self) -> None:
        for attempt in range(1, _RECONNECT_ATTEMPTS + 1):
            try:
                self._client = BleakClient(self._device)
                await self._client.connect()
                await self._client.start_notify(STATUS_CHAR_UUID, self._on_notify)
                return
            except BleakError:
                self._client = None
                if attempt == _RECONNECT_ATTEMPTS:
                    raise
                await asyncio.sleep(1.0)

    async def disconnect(self) -> None:
        if self._client is None or not self._client.is_connected:
            return
        try:
            await self._client.stop_notify(STATUS_CHAR_UUID)
        except BleakError:
            pass
        try:
            await self._client.disconnect()
        except BleakError:
            pass
        self._client = None

    async def send_command(self, cmd: bytes) -> None:
        if self._client is None or not self._client.is_connected:
            raise BleakError("Not connected to device.")
        await self._client.write_gatt_char(CONTROL_CHAR_UUID, cmd, response=True)

    async def start_recording(self) -> None:
        await self.send_command(CMD_RECORD_START)

    async def stop_recording(self) -> None:
        await self.send_command(CMD_RECORD_STOP)

    async def pause_recording(self) -> None:
        await self.send_command(CMD_RECORD_PAUSE)

    async def get_status(self) -> dict:
        await self.send_command(CMD_STATUS_REQUEST)
        await asyncio.sleep(_STATUS_WAIT)
        return self.last_status

    async def __aenter__(self) -> "BleConnection":
        await self.connect()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        await self.disconnect()
