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
- **UI:** Jetpack Compose
- **Local AI:** MediaPipe LLM Inference (Gemma 4 E4B), `whisper.cpp` JNI
- **Database:** Room + FTS4
- **Architecture:** MVVM + Clean Architecture principles

## Core Mandates & Conventions

### 1. AI & Transcription
- **No Audio to Claude:** Claude's API handles text only. **ALWAYS** use local Whisper for transcription. Claude is strictly for summarization, mind mapping, and translation of the resulting text.
- **Prompt Caching:** Utilize Anthropic's prompt caching for system prompts and category templates to optimize latency and cost.
- **Category 15 (Functionality):** This category involves 8 sequential sub-analyses. Chain these calls using conversation context where possible.

### 2. BLE Protocol
- **Discovery First:** BLE UUIDs in `src/ble/protocol.py` are placeholders. Run `scripts/ble_discover.py` on new hardware to identify real service/characteristic UUIDs.
- **Control Only:** BLE is for control (start/stop recording, status). Data transfer (MP3 files) happens via USB mass storage.

### 3. File System & Storage
- **USB-First:** Audio files are accessed directly from the recorder's exFAT filesystem (typically mounted at `/media/.../RECORD/`).
- **Android SAF:** On Android, use Storage Access Framework (SAF) for USB OTG access.

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
    - `ai/`: Local LLM and Whisper services.
    - `ble/`: Bluetooth management and FW920 protocol.
    - `ui/`: Compose screens and components.
    - `viewmodel/`: State management for UI.

## Documentation & Tracking
- **README.md:** Project overview and setup.
- **GEMINI.md:** Foundational mandates and architecture (this file).
- **ROADMAP.md:** Future feature development and backlog.
- **PLAN.md:** Original design document and strategy.
- **CLAUDE.md:** Environment-specific CLI guidance.

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
- Ensure `Gemma 4 E4B` model is downloaded/configured for local inference (see `ModelDownloader.kt`).

## Design Standards
- **Surgical Updates:** When modifying existing logic, maintain architectural consistency.
- **Explicit Types:** Use Python type hints and Kotlin's strong typing system rigorously.
- **Interactive CLI:** Use `rich` for all user-facing CLI output to ensure a polished experience.
- **Visuals:** Android UI should follow modern Material 3 guidelines, prioritizing responsiveness and accessible design.
