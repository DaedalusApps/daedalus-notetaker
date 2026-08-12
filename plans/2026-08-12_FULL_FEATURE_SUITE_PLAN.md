# Execution Plan — Daedalus Notetaker Full Feature Suite (6 Core Pillars)

**Date:** 2026-08-12  
**Goal:** Implement 6 core platform capabilities to elevate reliability, playback UX, search performance, and system integration.  
**Architecture Mandate:** All AI processing runs 100% locally on-device. No ADB execution during development. Each task includes ADB broadcast user stories for test automation.

---

## Pillar 1: Background Processing & Foreground Service
### Objectives
- Keep Whisper transcription and Gemma 3 AI analysis running reliably even when the app is minimized or the screen turns off.
- Surface a real-time Foreground Service Notification with progress bar (*"Processing 18m recording… 45%"*).

### Implementation
1. **`AnalysisForegroundService.kt`** (`com.daedalus.notes.ai`):
   - Extends `android.app.Service`.
   - Uses `startForeground()` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` / `SPECIAL_USE`.
   - Builds `NotificationCompat.Builder` with channel `daedalus_processing`.
   - Exposes StateFlow for current file and progress percentage.
2. **ViewModel Integration (`RecordingViewModel.kt`)**:
   - Binds `AnalysisForegroundService` during BLE auto-sync and `doAnalyzeExclusive`.
   - Releases foreground service upon completion or failure.

### ADB User Story Verification
- **User Story 1:** Trigger foreground service background processing manually via ADB:
  ```powershell
  adb shell am broadcast -a com.daedalus.notes.ANALYZE --es filename "20260812113220" -n com.daedalus.notes/.AdbReceiver
  ```
- **User Story 2:** Verify active notification:
  ```powershell
  adb shell dumpsys notification | Select-String -Pattern "daedalus"
  ```

---

## Pillar 2: Action Items → Android Calendar Integration
### Objectives
- One-tap button on extracted To-Dos / Action Items to add them directly to the native Android Calendar.

### Implementation
1. **`CalendarIntegration.kt`** (`com.daedalus.notes.util`):
   - Helper using `Intent(Intent.ACTION_INSERT)` with `CalendarContract.Events.CONTENT_URI`.
   - Pre-fills `TITLE`, `DESCRIPTION` (linking back to recording note), `EXTRA_EVENT_BEGIN_TIME`, and defaults to 1-hour duration.
2. **UI Layer (`TodoDetailScreen.kt` & `NoteDetailScreen.kt`)**:
   - Add "Add to Calendar" icon button on each To-Do card.
3. **`AdbReceiver.kt`**:
   - Add broadcast intent `com.daedalus.notes.ADD_CALENDAR` to trigger event creation via ADB.

### ADB User Story Verification
- **User Story 1:** Trigger calendar intent via ADB:
  ```powershell
  adb shell am broadcast -a com.daedalus.notes.ADD_CALENDAR --es title "Email Susan resume" --es note "20260812102746" -n com.daedalus.notes/.AdbReceiver
  ```

---

## Pillar 3: Enhanced Audio Player (Speed Controls & Skip)
### Objectives
- Add playback speed controls (`1.0x`, `1.25x`, `1.5x`, `2.0x`) and `+10s` / `-10s` skip buttons to ExoPlayer audio controls.

### Implementation
1. **`AudioPlayerState.kt` / `RecordingViewModel.kt`**:
   - Track `playbackSpeed: Float` StateFlow (persisted in SharedPreferences).
   - ExoPlayer setter: `exoPlayer.setPlaybackSpeed(speed)`.
2. **UI Controls (`NoteDetailScreen.kt` & `RecordingsScreen.kt`)**:
   - Add Speed Toggle Pill (`1.0x` → `1.25x` → `1.5x` → `2.0x`).
   - Add `-10s` (`Replay10`) and `+10s` (`Forward10`) IconButton controls with smooth seek.

### ADB User Story Verification
- **User Story 1:** Trigger playback speed change via ADB:
  ```powershell
  adb shell am broadcast -a com.daedalus.notes.SET_SPEED --ef speed 1.5 -n com.daedalus.notes/.AdbReceiver
  ```

---

## Pillar 4: Speaker Formatting & Paragraph Breaks
### Objectives
- Format Whisper transcripts with automatic paragraph breaks and turn-taking speaker tags (`Speaker 1:`, `Speaker 2:`) for readability.

### Implementation
1. **`SpeakerDiarizer.kt`** (`com.daedalus.notes.ai`):
   - Post-processes transcript segments using pause detection and pitch/spectral shift heuristics.
   - Assigns speaker labels (`Speaker 1`, `Speaker 2`) and inserts double newlines.
2. **UI Layer (`NoteDetailScreen.kt`)**:
   - Renders speaker badges with distinct avatar pill colors for each speaker turn.

### ADB User Story Verification
- **User Story 1:** Test speaker diarization formatting via ADB:
  ```powershell
  adb shell am broadcast -a com.daedalus.notes.FORMAT_SPEAKER --es filename "20260812102746" -n com.daedalus.notes/.AdbReceiver
  ```

---

## Pillar 5: Room FTS4 Search Engine & Knowledge Graph Optimization
### Objectives
- Full-text search (FTS4) indexing across titles, transcripts, summaries, topics, and mind-map nodes.
- Canvas virtualization and performance optimization for multi-part long recordings.

### Implementation
1. **`RecordingFtsEntity.kt`** (`com.daedalus.notes.data.db`):
   - Room FTS4 entity mapping `recordingId`, `title`, `transcript`, `summary`, `topics`, `mindMap`.
2. **`RecordingDao.kt`**:
   - Search query: `@Query("SELECT * FROM recordings JOIN recordings_fts ON recordings.filename = recordings_fts.docid WHERE recordings_fts MATCH :query")`.
3. **`GlobalMindMapScreen.kt`**:
   - Node caching and canvas rendering bounds optimization.

### ADB User Story Verification
- **User Story 1:** Perform FTS4 search query via ADB:
  ```powershell
  adb shell am broadcast -a com.daedalus.notes.SEARCH_FTS --es query "initiative" -n com.daedalus.notes/.AdbReceiver
  ```

---

## Pillar 6: Storage Repair & Audio Defragmentation Engine
### Objectives
- Scan MP3 frame headers (`Mp3FrameScan.kt`), trim truncated trailing bytes, rebuild sync frames, and repair duration headers.

### Implementation
1. **`AudioRepairEngine.kt`** (`com.daedalus.notes.data.model`):
   - Scans MP3 byte stream for valid frame headers (`0xFF 0xFB` / `0xFF 0xF3`).
   - Removes zero-padding or corrupted chunks from interrupted BLE downloads.
   - Re-writes clean MP3 file and updates duration in Room DB.

### ADB User Story Verification
- **User Story 1:** Trigger audio repair on a file via ADB:
  ```powershell
  adb shell am broadcast -a com.daedalus.notes.REPAIR_FILE --es filename "20260812113220" -n com.daedalus.notes/.AdbReceiver
  ```

---

## Phase Rollout Order
1. **Task 1:** Pillar 3 (Audio Player Speed & Skip) + Pillar 2 (Calendar Action Items)
2. **Task 2:** Pillar 1 (Background Service & Notification) + Pillar 4 (Speaker Formatting)
3. **Task 3:** Pillar 5 (Room FTS4 & Mind Map Perf) + Pillar 6 (Storage Repair Engine)
