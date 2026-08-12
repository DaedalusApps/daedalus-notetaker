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

Part keys are `{original}_p{N}` plus the parent's own extension — BLE-synced recordings have
none, imports keep `.mp3`, phone recordings are `.m4a` — so a part key never claims a container
the audio isn't in. Parts share the parent's `localPath`; no physical file splitting occurs.

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
             - Decodes only [startMs, endMs) to 16 kHz mono float PCM
             - Chunks that window into 30s Whisper segments
          b. Save Recording with parentFilename + partIndex
          c. Run analyzeTranscript() (Gemma summarization)
       4. Update parent with the joined transcript + part count in title/summary
```

A part with nothing readable in it is skipped rather than saved, so a trailing range with no
decodable audio doesn't become a blank card. FW920 files routinely contain damaged frames — a
45-minute recording typically loses 1–2 minutes to them — so the declared duration can exceed
what actually decodes. `redownloadAndAnalyze()` (the "Re-fetch from device" action) exists for
the severe case: ordinary sync skips any file that already exists locally, so a truncated
download can never repair itself.

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
- **DAO filtering**: `getAllFlow()`, `searchFlow()` and `getSince()` all exclude child parts
  (`parentFilename IS NULL`), so parts appear only nested under their parent in the UI and
  contribute their content exactly once to TODO extraction.
- **Parent rollup**: after a split the parent carries the joined transcript, a summary stitched
  from the part summaries, the union of the parts' topics, and an embedding computed from those
  summaries. Without the topics and embedding, every split recording embedded from the
  "Split into N parts…" boilerplate and Ask ranked them all identically.
- **Cascade delete**: Deleting a parent also deletes all its child parts (single
  `DELETE ... WHERE parentFilename = ?`). Deleting a *part* removes only its DB row — parts share
  the parent's audio file and do not exist on the FW920, so neither the file nor the device is touched.
- **Re-analysis**: Running analysis again on a parent deletes old parts and recreates them.

### Memory & Performance (estimated)
| Duration | Parts | Transcription Time | Analysis Time |
|----------|-------|---------------------|---------------|
| 20 min | 2 | ~2-3 min | ~2-3 min |
| 30 min | 2 | ~3-5 min | ~3-5 min |
| 60 min | 4 | ~6-10 min | ~6-10 min |

`decodeToPcmFloat(file, startMs, endMs)` keeps only the requested window and stops once it has
been read, so peak memory tracks the part (~58 MB for 15 min) rather than the file. Decoding the
whole file and slicing afterwards threw `OutOfMemoryError` on a 21-minute recording.

Decoding still *starts* at sample 0 for every part — there is no `MediaExtractor.seekTo` — so a
recording of N parts decodes N(N+1)/2 windows' worth of audio. Adding the seek means re-deriving
the absolute sample position from `extractor.sampleTime` after landing on a sync frame; get it
wrong and part boundaries drift silently, so it wants its own verification pass.

### Backup
`BackupManager` exports part rows alongside their parents via `RecordingDao.getAllForBackup()`
(the only recordings query that does *not* filter `parentFilename IS NULL`), carrying
`parentFilename` and `partIndex` so a restore re-nests them. Part analysis is not derivable from
the parent's rollup — the parent holds a stitched summary, not each part's title/mindMap/topics —
so exporting parents only silently discarded hours of Gemma work.

`backupVersion` stays **2**: import is key-presence-driven and never reads the version, which is
why v1 files still restore, so a bump would gate nothing. An *older* app reading a new file ignores
the two keys and restores parts as standalone recordings — they appear twice in the list, nested
and at top level. Data loss, no; downgrade-only cosmetic duplication, yes. Import falls back to the
existing row's linkage when the keys are absent, so restoring an old backup over a DB that already
has parts does not flatten them.

An imported `parentFilename` is only honoured when the named parent ships in the same payload and
the row being linked is not already a standalone recording locally — the two conditions every real
export satisfies, since parts are created solely by the split logic and never promoted from a
standalone row. Without that check a hand-edited backup could re-parent an existing recording, and
`deleteRecording()` reads `parentFilename` as "DB-only row sharing the parent's audio": the row
would disappear from the list while the audio stayed on disk *and* on the FW920, reported as a
successful delete. That the delete path derives a destructive decision from a column at all is the
deeper issue, still open: a better test is whether another row actually shares this row's
`localPath`.

### Analysis routing
Gemma 3 1B stops following the "return JSON" instruction once the prompt carries more than a
couple of thousand characters of raw transcript — it echoes the transcript instead, and the
parser falls back to slicing a title out of it ("and And- I-This What? Some's so let's…").
Measured on-device: 1,674 chars parsed cleanly; 3,935 and 10,681–11,937 degraded, 5 for 5.

So the JSON step never sees raw transcript above `DIRECT_ANALYSIS_MAX_CHARS` (2,000). Everything
longer is summarized into bullets first, and the bullet stage caps its chunk budget at
`SUMMARY_CHUNK_BUDGET` (6,000) regardless of the user's AI text budget — bulletizing alone was
not enough, because one 10k chunk produced 4,274 chars of bullets and the JSON step degraded on
those too. What matters is how much text reaches the JSON step; chunk size is the lever.

### Known gaps
- **Ask / library Q&A and the knowledge graph** read `allRecordings`, which excludes parts. They
  see the parent's rollup (joined transcript, stitched summary, merged topics, embedding) but not
  the per-part embeddings, so retrieval is per-recording rather than per-part.
- **Synthesis input is bounded only indirectly.** 2,437 chars of bullets parsed cleanly and 4,274
  did not; the boundary between them is unmeasured. If a part ever degrades again, bound the
  synthesis input directly (re-summarize the bullets) instead of relying on chunk size.
- **Playback** — parts reference the same physical file. `NoteDetailScreen` plays from the start
  of the file; seeking to the part's offset is a future improvement.
