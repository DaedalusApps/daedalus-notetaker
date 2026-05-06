# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`notetaker` — Python CLI that connects to an ELVANZA FW920 AI voice recorder and processes recordings with AI. Phase 1 is a Python prototype (this repo); Phase 2 is an Android port.

## Environment

- **Python**: 3.13.7 (`/usr/bin/python3`) — installed as system package, `--break-system-packages` required for pip
- **pip**: bootstrapped via `get-pip.py`; use `python3 -m pip install --break-system-packages`
- **Git**: `/usr/bin/git`
- **No Node/npm/Docker** on this machine

## Commands

```bash
# Install dependencies
python3 -m pip install -r requirements.txt --break-system-packages

# Run the CLI (always from project root)
python3 src/cli.py --help
python3 src/cli.py scan              # BLE scan for FW920
python3 src/cli.py connect           # connect and show device status
python3 src/cli.py record start      # send BLE start recording command
python3 src/cli.py list              # list recordings from USB drive
python3 src/cli.py process <file>    # run full AI pipeline
python3 src/cli.py export <file> --format md,pdf,docx,srt

# Run tests
python3 -m pytest tests/ -v

# BLE discovery (run once, device powered on, before BLE commands work)
python3 scripts/ble_discover.py
```

## Architecture

```
src/
├── categories.py          # 15 recording categories + FUNCTIONALITY_PROMPTS (8 sub-analyses)
├── cli.py                 # rich CLI entry point; dispatches all commands
├── ai/
│   ├── claude_client.py   # Anthropic SDK wrapper with prompt caching (text/JSON only)
│   ├── transcription.py   # MP3 → text via openai-whisper (NOT Claude — no audio API)
│   ├── srt.py             # MP3 → timestamped .srt via openai-whisper
│   ├── summarization.py   # transcript → category-aware JSON summary via Claude
│   ├── functionality.py   # Category 15: 8 sequential Claude analyses
│   ├── mindmap.py         # transcript → JSON graph → Mermaid + Markdown
│   └── translation.py     # transcript → target language via Claude
├── ble/
│   ├── scanner.py         # async BleakScanner for "FW920" device name
│   ├── connection.py      # BleakClient wrapper with auto-reconnect
│   └── protocol.py        # UUIDs + command bytes (PLACEHOLDERS — update after discovery)
├── storage/
│   └── drive_accessor.py  # finds exFAT USB drive at /media/.../RECORD/
└── export/
    ├── markdown_exporter.py
    ├── pdf_exporter.py     # fpdf2
    ├── docx_exporter.py    # python-docx
    └── gdrive_sync.py      # Google Drive API v3 OAuth upload
scripts/
└── ble_discover.py         # one-shot GATT dump — run this first to find UUIDs
```

## Key Design Decisions

- **Claude does NOT receive audio**: Claude's API only handles text/images/PDFs. All transcription uses `openai-whisper` locally. Claude handles summarization, mind maps, and translation on the resulting text.
- **Prompt caching**: `claude_client.complete()` caches the system prompt block by default. Static prompts in `categories.py` are cache-friendly (don't change between calls).
- **BLE UUIDs are placeholders**: `src/ble/protocol.py` has placeholder UUIDs. Run `scripts/ble_discover.py` with the device powered on to get real UUIDs, then update `protocol.py`.
- **USB-first**: File access is via direct filesystem path (device mounts as exFAT). BLE is control-only (start/stop recording, status).
- **sys.path**: `cli.py` inserts the project root at runtime so `from src.*` imports work when invoked as `python3 src/cli.py`.

## BLE Protocol Discovery

Run once with the FW920 recorder powered on:
```bash
python3 scripts/ble_discover.py
# Output: ble_discovery_output.json + rich table in terminal
# Update src/ble/protocol.py SERVICE_UUID, CONTROL_CHAR_UUID, STATUS_CHAR_UUID with real values
```

## Setup for First Use

```bash
cp .env.example .env
# Edit .env: add ANTHROPIC_API_KEY=sk-ant-...

# openai-whisper is large (requires PyTorch ~500MB first download):
python3 -m pip install openai-whisper --break-system-packages
```
