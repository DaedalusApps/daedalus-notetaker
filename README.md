# Daedalus Notetaker

Companion application for the **ELVANZA FW920** (HUXGO OEM) AI voice recorder.

## Overview

Daedalus Notetaker bridge the gap between your physical voice recorder and AI-powered insights. It provides a seamless workflow for controlling your device via Bluetooth, importing recordings over USB, and generating high-quality transcripts, summaries, and mind maps.

The project is developed in two phases:
1.  **Phase 1:** A robust Python CLI prototype for rapid protocol validation and desktop-class processing.
2.  **Phase 2:** A native Android application (Kotlin/Compose) for on-device inference and mobile-first convenience.

## Key Features

-   **BLE Remote Control:** Start, stop, and pause recordings directly from your phone or computer.
-   **Local Transcription:** Uses `openai-whisper` (desktop) and `whisper.cpp` (Android) for 100% private, local transcription. **Audio never leaves your device.**
-   **AI Analysis:** 
    *   15 specialized recording categories (Medical, Legal, Meetings, Education, etc.).
    *   Automatic summarization, action item extraction, and perspective analysis.
    *   Interactive mind map generation (Markdown/Mermaid).
-   **On-Device AI (Android):** Powered by Google MediaPipe and Gemma 4 E4B for completely offline analysis.
-   **Multi-Format Export:** Export to Markdown, PDF, DOCX, and SRT (timestamps).
-   **Cloud Sync:** Optional Google Drive synchronization.

## Project Structure

-   `src/`: Python source code (Phase 1).
-   `android/`: Kotlin/Jetpack Compose Android project (Phase 2).
-   `scripts/`: BLE protocol discovery and reverse-engineering tools.
-   `reverse/`: Analysis artifacts from device firmware and OEM app.

## Getting Started (Python CLI)

### Prerequisites
-   Python 3.13+
-   `ffmpeg` (for audio processing)

### Setup
1.  Clone the repository.
2.  Install dependencies:
    ```bash
    pip install -r requirements.txt
    ```
3.  Configure your environment:
    ```bash
    cp .env.example .env
    # Add your ANTHROPIC_API_KEY for cloud analysis
    ```

### Usage
```bash
# Scan for the recorder
python src/cli.py scan

# Start a recording via BLE
python src/cli.py record start

# Process a recording from the USB drive
python src/cli.py process /media/user/RECORD/REC001.MP3
```

## Getting Started (Android)

1.  Open the `android/` directory in Android Studio.
2.  Build and install the APK on an ARM64 device (e.g., S24 Ultra).
3.  Follow the in-app prompts to download the local Gemma AI model (~1.4GB - 2.5GB).

## Documentation

For detailed architectural mandates and development workflows, see [GEMINI.md](./GEMINI.md).

## License

[MIT License](LICENSE) (or your preferred license)
