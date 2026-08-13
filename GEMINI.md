# GEMINI.md - Notetaker Project Guidance

This file provides foundational mandates, architecture, and workflows for the `notetaker` project. It takes precedence over general defaults.

## Project Overview
`notetaker` is a companion application for the **ELVANZA FW920** (HUXGO OEM) AI voice recorder. The project is split into two phases:
1. **Phase 1 (Current):** A Python CLI prototype for BLE control, transcription (Whisper), and Claude-based analysis.
2. **Phase 2 (Active):** An Android app (Kotlin/Compose) for on-device inference and mobile-first experience.

## Tech Stack

### Python Prototype (Phase 1)
- **Runtime:** Python 3.13.7
- **BLE:** `bleak` (async)
- **Transcription:** `openai-whisper` (local)
- **AI Analysis:** Anthropic SDK (`claude-sonnet-4-6`)
- **Audio:** `pydub`, `ffmpeg`
- **Export:** `fpdf2` (PDF), `python-docx` (DOCX), Google Drive API
- **CLI:** `rich`

### Android App (Phase 2)
- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Local AI:** MediaPipe LLM Inference (Gemma 3 1B), sherpa-onnx (Whisper)
- **Database:** Room + FTS4
- **Architecture:** MVVM + Clean Architecture principles
- **Versioning:** Automated via git commit count (`versionCode`) and `android/version.properties` (`versionName`).

## Core Mandates & Conventions

### 1. AI & Transcription
- **On-Device Only:** Transcription (Whisper) and Analysis (Gemma 3) must run locally on the phone. Do not send audio or transcripts to external APIs in the Android app.
- **Model Storage:** Models are downloaded to `getExternalFilesDir(null)/models/` on first launch.

### 2. BLE Protocol (confirmed via HCI snoop of DOWAY reference app)

**UUIDs:**
- Service: `0000b0b0-0000-1000-8000-00805f9b34fb`
- Write: `0000b0b1-...` — send commands here
- Notify B0B2: control responses (A0 0A 01 packets)
- Notify B0B3/B0B4: audio data chunks during download (only B0B4 observed in practice)

**Packet format:** `A0 0A 01 [CMD] [LEN] [PAYLOAD...] [CRC16-ARC-hi] [CRC16-ARC-lo]`

**Key commands:**
| CMD  | Direction | Purpose |
|------|-----------|---------|
| 0x01 | →device   | Get serial number |
| 0x02 | →device   | Get firmware version |
| 0x04 | →device   | Sync time |
| 0x05 | →device   | Get storage/battery status |
| 0x06 | →device   | Start recording |
| 0x07 | →device   | Confirm done (after stop) |
| 0x08 | →device   | Stop recording |
| 0x0A | →device   | List files (device replies with one 0x0A packet per file, null-entry = end) |
| 0x0B | →device   | **Download file** — payload: 14-byte filename (space-padded) + 4-byte BIG-endian start offset (app sends 0x00000000) |
| 0x0D | →device   | **Delete file** — payload: 14-byte filename (space-padded) |

**Download protocol (cmd=0x0B):**
1. Send `buildPacket(0x0B, nameBytes + [0,0,0,0])` — 14-byte space-padded filename, zero offset
2. Device responds `Ack(0x0B)` = "ready"
3. Device streams `AudioChunk` packets on B0B3/B0B4
4. Device sends second `Ack(0x0B)` = end-of-file
5. **Do not send any confirm/continue packet** — one request, device sends complete file

**MTU:** Request MTU 512 before service discovery (`gatt.requestMtu(512)` → `onMtuChanged` → `gatt.discoverServices()`). Note the FW920 only grants **247** (`MTU changed to 247`), so notification payloads cap at 244 bytes.

**Download offset is BIG-endian**, not little-endian (verified 2026-08-11 by probing: sending `00 00 01 00` seeked to byte 256, and `00 01 00 00` seeked to 65,536 — byte-exact both times). The field has always been sent as zeros, so the byte order never mattered until now. The device honours arbitrary start offsets, which makes a resume/repair mechanism feasible. There is **no length field** — the device streams from the offset to end of file.

**Audio arrives on B0B4 only** (B0B3 was never observed carrying data). Chunks are raw MP3 with no framing — chunk 1 of a transfer begins with an MP3 frame sync (`FF F3 ...`) — so payloads are appended verbatim.

**Logical blocks are fixed 512 bytes, arriving as `244 + 244 + 24`** under the 247-byte MTU (`maxPayload` = mtu - 3 = 244). This was the original finding and it is correct: the same 62,680-byte file transferred three times, byte-identical each time (MD5 `7eb5e3a9a1c8c886642e748c56f97727`), decomposes exactly as 122 × 512 + a 216-byte tail once notification coalescing (below) is undone.

**Android's BLE stack coalesces adjacent notifications non-deterministically — notification sizes are NOT a usable loss signal.** The same lossless file produced 362 notifications in one run (`244B x244, 24B x112, 48B x5, 216B x1`) and 361 in another (`244B x244, 24B x110, 48B x6, 216B x1`) — identical bytes, identical MD5, differing only in how many 24-byte block tails arrived merged into 48-byte chunks. Short chunks can therefore appear mid-block in arrival order (observed: `244,244,244,24,244,24`), so a short chunk is not a reliable block delimiter. A cadence-based loss detector (`TransferLossDetector`, since removed) was built on the multiple-of-512 assumption and reported 16,384 bytes lost, then 14,336 after correction, on this provably lossless file — do not rebuild this approach. Detecting genuine interior loss needs a content-level check, e.g. MP3 frame-header validation, not notification-size cadence.

**That same transfer was lossless.** A 62 KB file re-fetched over BLE matched the previous copy byte for byte across three transfers, and an ffmpeg survey of five files on the device found 0, 0, 2, 6 and 523 decode errors.

**Do not send other commands during a transfer.** The device answers the new command instead of continuing the stream. A 15s status poller (`CMD 0x05`) used to fire mid-download; `BleManager.transferInProgress` now suspends it.

**CONFIRMED ON HARDWARE 2026-08-12 (#96): both `Ack(0x0B)` packets in the download protocol — "ready" and end-of-file — arrive on B0B2, never on B0B3/B0B4.** `FW920Protocol.parseResponse` (via `BleManager.handleIncoming`) routes purely on which characteristic delivered the notification: bytes from B0B3/B0B4 are always treated as audio, regardless of content, so a raw MP3 chunk that coincidentally begins `A0 0A` and satisfies the control-packet length check is never misparsed as control (previously ~1 in 2^24 chunks, not the ~1 in 65,536 the issue estimated). Evidence from a real download: `RX char=0000b0b2-... [12b]: A0 0A 01 0B 05 00 00 00 F4 D8 45 E1` (ready ack) and `RX char=0000b0b2-... [8b]: A0 0A 01 0B 01 02 70 CE` (EOF ack) — both on B0B2. The transfer completed `totalBytes=62680`, MD5 `7eb5e3a9a1c8c886642e748c56f97727`, byte-identical to the known-good copy, `frameScan=436 frames, 0 gaps, 0 bytes (0.00%)`. B0B3 delivered no data at all during that run. If an ack ever arrived on B0B4, it would be written into the audio file as raw bytes, corrupting the frame sync and preventing EOF detection (transfer stalls to the idle timeout, file left truncated) — worse than the defect being fixed; that is why `BleManager.kt`'s RX log always includes the characteristic UUID and B0B3 carries a one-time warning log the first time it ever delivers anything.

**Requesting a file the device does not have** returns `Ack(0x0B)` ("ready") followed by silence — no error code. Only a timeout distinguishes it from a dead transfer, so check `collectFileList()` first.

**File list `sizeBytes` field is NOT in bytes** — it reports 1,454,612 for a file that transfers as ~1,323,518. Do not use it for size comparison. A clean `Ack(0x0B)` after data means the device thinks it is done, **not** that the file is intact.

**ADB test automation:**
```powershell
# Trigger sync:
adb shell am broadcast -a com.daedalus.notes.SYNC -n com.daedalus.notes/.AdbReceiver
# Start/stop recording:
adb shell am broadcast -a com.daedalus.notes.START_RECORDING -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.STOP_RECORDING -n com.daedalus.notes/.AdbReceiver
# Trigger analysis for a specific file (filename must match the DB key exactly —
# BLE-synced recordings have no extension, imported files keep theirs):
adb shell am broadcast -a com.daedalus.notes.ANALYZE --es filename "20260524213434" -n com.daedalus.notes/.AdbReceiver
```
`AdbReceiver` (exported manifest receiver) re-broadcasts to same package UID, bypassing `RECEIVER_NOT_EXPORTED` on MainActivity's dynamic receiver. It only forwards — the app must be in the foreground so `MainActivity`'s receiver is registered, and every action then runs the same code path the UI uses.

### 3. File System & Storage
- **BLE-First:** Audio files are downloaded via BLE (cmd=0x0B) into `getExternalFilesDir(null)/Recordings/`. USB OTG path is legacy/fallback only.
- **Android SAF:** On Android, use Storage Access Framework (SAF) for USB OTG access if needed.

## Architecture

### Python Structure
- `src/cli.py`: Main entry point.
- `src/ai/`: Wrappers for Whisper and Claude.
- `src/ble/`: BLE communication logic.
- `src/storage/`: Filesystem access for the recorder.
- `src/export/`: Exporters for various formats.
- `src/categories.py`: Source of truth for 15 recording categories and their prompts.

### Android Structure
- `android/app/src/main/java/com/daedalus/notes/`:
    - `ai/`: Local LLM (Gemma 3) and Transcription (Whisper) services.
    - `ble/`: Bluetooth management and FW920 protocol.
    - `ui/`: Compose screens and components.
    - `viewmodel/`: State management for UI.

## Documentation & Tracking
- **README.md:** Project overview and setup.
- **GEMINI.md:** Foundational mandates and architecture (this file).
- **ROADMAP.md:** Future feature development and backlog.
- **PLAN.md:** Original design document and strategy.
- **android/BUILD.md:** Android build and environment documentation.
- **android/docs/SPLIT_RECORDINGS.md:** How recordings >15 min are split into parts.

## Workflows

### Setup & Development
```bash
# Python dependencies (system packages might require --break-system-packages)
python3 -m pip install -r requirements.txt --break-system-packages

# BLE Discovery
python3 scripts/ble_discover.py

# Testing
python3 -m pytest tests/ -v
```

### Android Development
- Open the `android/` directory in Android Studio.
- Ensure `Gemma 3 1B` and `Whisper base.en` models are downloaded (see `ModelDownloader.kt` and `WhisperDownloader.kt`).

### Testing (Android)
```bash
cd android
# Run Unit Tests
.\gradlew :app:testDebugUnitTest

# Run Instrumented (UI) Tests
.\gradlew :app:connectedDebugAndroidTest
```
Maintain the regression test suite:
- `RecordingsScreenTest.kt`: Recording list, selection mode, swipe-to-delete.
- `AskHomeScreenTest.kt`: Ask landing screen and library Q&A flow.
- `GlobalMindMapScreenTest.kt`: Knowledge Graph rendering.
- `SmartAnalysisParserTest.kt`: AI response normalization.
- `RecordingDaoTest.kt`: Database integrity.

## Engineering Principles

- **Think Before Coding:** State assumptions clearly. If a requirement is ambiguous, ask for clarification before implementing. Surface trade-offs and push back on over-engineering.
- **Minimalism & Simplicity:** Write the minimum code necessary. Avoid speculative features or premature abstractions. If a senior engineer would call it overcomplicated, simplify it.
- **Goal-Driven Execution:** Transform vague tasks into verifiable goals with a clear plan.
    - **"Fix the bug"** → Reproduce with a test, then make it pass.
    - **"Add feature X"** → Define success criteria, implement, and verify with tests.

## Design Standards

- **Surgical Updates:** Touch only what is necessary. Every changed line must trace directly to the request. Respect existing code style and formatting.
- **Explicit Types:** Use Python type hints and Kotlin's strong typing system rigorously.
- **Interactive CLI:** Use `rich` for all user-facing CLI output to ensure a polished experience.
- **Visuals:** Android UI should follow modern Material 3 guidelines, prioritizing responsiveness and accessible design.
- **Accessibility & Testability:** Prefer rendering UI nodes as Composables (e.g., `Text`) over direct `Canvas.drawText` to ensure they are discoverable by the semantics tree and screen readers.
- **Cleanup:** Only remove imports, variables, or functions that your changes made obsolete. Do not delete pre-existing dead code unless explicitly asked.

