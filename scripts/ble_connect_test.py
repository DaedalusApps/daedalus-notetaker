"""Direct connection test using the DOWAY-extracted GATT UUIDs.

Bypasses bluetoothctl pairing — just connects via bleak, subscribes to the
notify characteristic, sends a few probe commands, and prints whatever the
device responds with. Run this with the FW920 powered on and blue LED
flashing (BT search mode).
"""

import asyncio
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
from rich.console import Console

from src.ble.protocol import (
    SERVICE_UUID,
    WRITE_CHAR_UUID,
    NOTIFY_CHAR_UUID,
    CMD_RECORD_START,
    CMD_RECORD_STOP,
    CMD_STATUS_REQUEST,
)

DEVICE_NAME = "FW920"
console = Console()


def on_notify(char, data: bytearray) -> None:
    console.print(f"  [bold green]NOTIFY[/bold green] {char.uuid}: [yellow]{bytes(data).hex()}[/yellow]  ({len(data)} bytes)")


async def main() -> None:
    console.print(f"[cyan]Scanning for {DEVICE_NAME}…[/cyan]")
    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=15.0)
    if device is None:
        console.print(f"[red]{DEVICE_NAME} not found.[/red] Power-cycle the device, hold power 8 sec to reset BT (blue LED flashing), and try again.")
        return

    console.print(f"[green]Found:[/green] {device.name} ({device.address})")
    console.print(f"[cyan]Connecting (timeout 30s)…[/cyan]")

    try:
        async with BleakClient(device, timeout=30.0) as client:
            console.print(f"[green]Connected![/green] Discovering services…")

            # Confirm our RE'd service is present
            target_service = None
            for svc in client.services:
                if str(svc.uuid).lower() == SERVICE_UUID.lower():
                    target_service = svc
                    break

            if target_service is None:
                console.print(f"[red]Service {SERVICE_UUID} NOT FOUND on this device.[/red]")
                console.print("All services advertised:")
                for svc in client.services:
                    console.print(f"  • {svc.uuid}")
                    for char in svc.characteristics:
                        console.print(f"      {char.uuid}  [{', '.join(char.properties)}]")
                return

            console.print(f"[green]✓ DOWAY service found:[/green] {target_service.uuid}")
            for char in target_service.characteristics:
                console.print(f"  • {char.uuid}  [{', '.join(char.properties)}]")

            # Subscribe to notifications first
            console.print(f"\n[cyan]Subscribing to notify characteristic…[/cyan]")
            await client.start_notify(NOTIFY_CHAR_UUID, on_notify)

            # Give device a moment to send any auto-status notifications
            console.print("[dim]Listening 3 seconds for spontaneous notifications…[/dim]")
            await asyncio.sleep(3)

            # Probe with a few command bytes
            for label, cmd in [
                ("0x01 (start?)", CMD_RECORD_START),
                ("0x02 (stop?)", CMD_RECORD_STOP),
                ("0x10 (status?)", CMD_STATUS_REQUEST),
            ]:
                console.print(f"\n[cyan]Writing {label} → {WRITE_CHAR_UUID}[/cyan]")
                try:
                    await client.write_gatt_char(WRITE_CHAR_UUID, cmd, response=True)
                    console.print(f"  [green]ack[/green]")
                except Exception as exc:
                    console.print(f"  [red]write failed:[/red] {exc}")
                await asyncio.sleep(2)  # give device time to respond

            await client.stop_notify(NOTIFY_CHAR_UUID)
            console.print("\n[green]Test complete.[/green]")

    except BleakError as exc:
        console.print(f"[red]BLE error:[/red] {exc}")
    except asyncio.TimeoutError:
        console.print(f"[red]Connection timed out.[/red] Likely causes: device is bonded to another client, or device's advertising window is too short.")


if __name__ == "__main__":
    asyncio.run(main())
