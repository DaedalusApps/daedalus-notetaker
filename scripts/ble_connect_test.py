"""
FW920 BLE connection test — forces LE transport, subscribes within 5s window.

Device behaviour (confirmed via nRF Connect):
- Connects on LE 1M, upgrades to LE 2M
- Disconnects after ~5s if central doesn't subscribe to notifications
- FFD0 service is the main control channel
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
    SERVICE_UUID, WRITE_CHAR_UUID, NOTIFY_CHAR_UUID, BIDIR_CHAR_UUID,
    BATTERY_CHAR_UUID, B0B0_SERVICE_UUID, B0B2_CHAR_UUID,
    CMD_STATUS_REQUEST,
)

DEVICE_NAME = "FW920"
console = Console()
received: list[tuple[str, bytes]] = []


def on_notify(char, data: bytearray) -> None:
    hex_str = bytes(data).hex()
    received.append((str(char.uuid), bytes(data)))
    console.print(f"  [bold green]NOTIFY[/bold green] {char.uuid}: [yellow]{hex_str}[/yellow]")


async def main() -> None:
    console.print(f"[cyan]Scanning for {DEVICE_NAME}…[/cyan]")

    # Keep scanner running while connecting (Android TRANSPORT_LE equivalent)
    detected = asyncio.Event()
    captured = {"device": None}

    def on_detect(device, adv):
        if device.name and DEVICE_NAME in device.name and not detected.is_set():
            captured["device"] = device
            detected.set()

    async with BleakScanner(detection_callback=on_detect) as scanner:
        await asyncio.wait_for(detected.wait(), timeout=15.0)

    device = captured["device"]
    console.print(f"[green]Found:[/green] {device.name} ({device.address})")
    console.print("[cyan]Connecting (LE transport, 15s timeout)…[/cyan]")

    try:
        async with BleakClient(device, timeout=15.0) as client:
            console.print(f"[bold green]CONNECTED![/bold green]")

            # Print all services
            for svc in client.services:
                console.print(f"  [dim]Service:[/dim] {svc.uuid}")
                for char in svc.characteristics:
                    console.print(f"    {char.uuid}  [{', '.join(char.properties)}]")

            # Subscribe to all notify characteristics IMMEDIATELY (within 5s window)
            console.print("\n[cyan]Subscribing to all notify chars…[/cyan]")
            notify_uuids = [
                NOTIFY_CHAR_UUID, BIDIR_CHAR_UUID, B0B2_CHAR_UUID,
                "0000b0b3-0000-1000-8000-00805f9b34fb",
                "0000b0b4-0000-1000-8000-00805f9b34fb",
                "0000c0c2-0000-1000-8000-00805f9b34fb",
                "e49a3003-f69a-11e8-8eb2-f2801f1b9fd1",
                BATTERY_CHAR_UUID,
            ]
            for uuid in notify_uuids:
                try:
                    await client.start_notify(uuid, on_notify)
                    console.print(f"  [green]✓[/green] subscribed {uuid}")
                except Exception as e:
                    console.print(f"  [dim]skip {uuid}: {e}[/dim]")

            # Read battery
            try:
                batt = await client.read_gatt_char(BATTERY_CHAR_UUID)
                console.print(f"\n[green]Battery:[/green] {batt[0]}%")
            except Exception:
                pass

            # Probe with status request
            console.print(f"\n[cyan]Writing status request → {WRITE_CHAR_UUID}[/cyan]")
            try:
                await client.write_gatt_char(WRITE_CHAR_UUID, CMD_STATUS_REQUEST, response=False)
                console.print("  [green]sent[/green]")
            except Exception as e:
                console.print(f"  [red]write failed:[/red] {e}")

            console.print("\n[dim]Listening 10s for responses…[/dim]")
            await asyncio.sleep(10)

            console.print(f"\n[bold]Received {len(received)} notifications total.[/bold]")
            for uuid, data in received:
                console.print(f"  {uuid}: {data.hex()}")

    except asyncio.TimeoutError:
        console.print("[red]Connection timed out.[/red]")
        console.print("\n[yellow]Trying gatttool as fallback…[/yellow]")
        console.print("Run: [bold]gatttool -b REDACTED_MAC -t public -I[/bold]")
        console.print("Then type: [bold]connect[/bold]")
    except BleakError as e:
        console.print(f"[red]BLE error:[/red] {e}")


if __name__ == "__main__":
    asyncio.run(main())
