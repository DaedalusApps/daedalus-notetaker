# Daedalus Notes

An Android companion app for the **ELVANZA FW920** voice recorder. Syncs recordings over BLE, transcribes them on-device with Whisper, and generates AI summaries and mind maps with Gemma 3 — no cloud required.

## Features

- **BLE Sync** — Download recordings wirelessly from the FW920, skipping files already on-device
- **On-Device Transcription** — Whisper base.en via sherpa-onnx; audio never leaves your phone
- **AI Analysis** — Gemma 3 1B generates a title, summary, topics, and a structured mind map per recording
- **Knowledge Graph** — Visualize connections across all recordings by shared topics
- **Full-Text Search** — Search across all transcripts and summaries
- **Export** — Share notes as Markdown

## Requirements

| | |
|---|---|
| **Hardware** | ELVANZA FW920 (HUXGO OEM) voice recorder |
| **Android** | ARM64, API 26+ (Android 8.0), Bluetooth LE |
| **Storage** | ~750 MB free (Gemma 3 1B: ~555 MB · Whisper base.en: ~160 MB) |

Tested on Samsung Galaxy S24 Ultra.

## Setup

1. Open the `android/` folder in Android Studio (Hedgehog or newer)
2. Build and run on your device, or via the command line:
   ```bash
   # Windows
   cd android && .\gradlew installDebug

   # macOS / Linux
   cd android && ./gradlew installDebug
   ```
3. On first launch the app will prompt you to download the AI models from Settings:
   - **Gemma 3 1B** (~555 MB) — on-device summarization
   - **Whisper base.en** (~160 MB) — on-device speech-to-text
4. Power on the FW920 — the app scans and connects automatically via BLE

## How It Works

```
FW920 recorder ──BLE──► Android app
                              │
                         Whisper STT
                         (on-device)
                              │
                         Gemma 3 1B
                         (on-device)
                              │
                   Title · Summary · Topics
                         Mind Map
```

All processing is local. No data is sent to external servers.

## Tech Stack

| Component | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM · Room · StateFlow |
| On-device LLM | MediaPipe 0.10.35 + Gemma 3 1B (`.task` format) |
| Transcription | sherpa-onnx 1.13.2 + Whisper base.en int8 ONNX |
| BLE | Android BluetoothLE — custom FW920 protocol |
| Database | Room 2.6.1 + SQLite WAL |
| Build | AGP 8.7.3 · Kotlin 2.0.21 · JDK 21 |

## Project Structure

```
android/     Kotlin/Compose Android app (primary)
src/         Python CLI prototype — BLE exploration & desktop processing
reverse/     FW920 protocol reverse-engineering notes and tools
```

## Building

Requires Android SDK 35 and JDK 21.

```bash
cd android
.\gradlew assembleDebug    # debug APK
.\gradlew assembleRelease  # release APK (ADB debug hooks disabled)
```

## Notes

- The FW920 uses a proprietary BLE GATT profile. Protocol details are documented in [`reverse/`](./reverse/).
- The Python CLI in `src/` is a Phase 1 prototype used for initial BLE discovery; the Android app is the primary product.
- ADB broadcast commands (`ANALYZE`, `SYNC`, etc.) are only active in debug builds and disabled in release.

## License

MIT
