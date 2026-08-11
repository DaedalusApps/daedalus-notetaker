# Long Recording Split (>15 Minutes)

## Overview
Recordings longer than 15 minutes are automatically split into independent 15-minute
parts during analysis. Each part gets its own Whisper transcription and Gemma analysis.
The original audio file is preserved intact for full playback.

## Architecture

### Data Model
Two columns added to `Recording` entity (DB version 11):
- `parentFilename: String?` — non-null marks this as a child part of a longer recording
- `partIndex: Int` — 1-based part number (0 = standalone recording)

Part filenames follow the convention `{original}_p{N}.mp3` (e.g. `20260524213434_p1.mp3`).
Parts share the same `localPath` as the parent — no physical file splitting occurs.

### Pipeline Flow
```
doAnalyze(filename)
  │
  ├─ duration ≤ 15 min → normal single-file transcription + analysis
  │
  └─ duration > 15 min → split path:
       1. Compute numParts = ceil(duration / 15min)
       2. Delete any stale parts from previous runs
       3. For each part:
          a. TranscriptionService.transcribeRange(file, startMs, endMs)
             - Decodes full file to PCM
             - Slices to [startSample, endSample)
             - Chunks the slice into 30s Whisper windows
          b. Save Recording with parentFilename + partIndex
          c. Run analyzeTranscript() (Gemma summarization)
       4. Update parent with part count in title/summary
```

### Key Files
| File | Role |
|------|------|
| `Recording.kt` | Entity with `parentFilename`, `partIndex` |
| `AppDatabase.kt` | Migration 10→11 |
| `RecordingDao.kt` | `getPartsOf()` query; main list filters `parentFilename IS NULL` |
| `RecordingRepository.kt` | `getPartsOf()` passthrough |
| `TranscriptionService.kt` | `transcribeRange(file, startMs, endMs)` |
| `RecordingViewModel.kt` | Split logic in `doAnalyze()`, cascade delete |
| `RecordingsScreen.kt` | Expandable parent → part cards UI |

### Constants
- `PART_DURATION_MS = 15 * 60 * 1000` (15 minutes, hardcoded in `RecordingViewModel.kt`)

### Behaviors
- **DAO filtering**: `getAllFlow()` and `searchFlow()` exclude child parts (`parentFilename IS NULL`)
  so they only appear nested under parents in the UI.
- **Cascade delete**: Deleting a parent also deletes all its child parts.
- **Re-analysis**: Running analysis again on a parent deletes old parts and recreates them.
- **Playback**: Parts reference the same physical file. `NoteDetailScreen` plays from the
  start of the file. Seeking to the part's offset is a future improvement.

### Memory & Performance (estimated)
| Duration | Parts | Transcription Time | Analysis Time |
|----------|-------|---------------------|---------------|
| 20 min | 2 | ~2-3 min | ~2-3 min |
| 30 min | 2 | ~3-5 min | ~3-5 min |
| 60 min | 4 | ~6-10 min | ~6-10 min |

Note: `transcribeRange()` decodes the full file each time it's called (once per part).
A future optimization could decode once and slice multiple times.
