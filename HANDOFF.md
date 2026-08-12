# Handoff Brief — Daedalus Notetaker

## Goal
Fix BLE synchronization for newly created recordings and maintain hardened state.

## Status & Branches
- **`main`**: Clean working tree, up-to-date with `origin/main`.
- **Latest Commit**: `fix(sync): re-download incomplete recordings with zero duration instead of skipping them` (`f60a15b`).

## Key Root Causes & Fixes Solved
1. **Incomplete Recording Skip Bug (`RecordingViewModel.kt`):**
   - Line 399 evaluated `if (localSize > 0) return@forEach`. If a file download was interrupted partway through (e.g. 649 KB downloaded of a 28 MB recording), `localSize > 0` was `true`, causing `syncAllBleFiles` to permanently skip re-downloading the file.
   - **Fix:** Changed the duplicate check to require `isComplete = existing != null && existing.durationMillis > 0L && localFile.exists() && localFile.length() > 0L`. Incomplete fragments (`durationMillis == 0L`) are now re-downloaded to completion.

2. **CRC Validation Fallthrough Bug in `FW920Protocol.kt`:**
   - Command response packets (`0xA0 0x0A ...`) sent by physical FW920 hardware failed strict CRC-16 checks due to hardware-side calculation differences.
   - Line 156 dropped valid `0xA0 0x0A` response packets as `AudioChunk`, causing `collectFileList()` to reject all 13 file entry notifications from the recorder and report `0 files`.
   - **Fix:** Removed strict CRC dropping on `0xA0 0x0A` packets with valid header and payload length.

3. **Dynamic Filename Parsing:**
   - Calculated filename length dynamically (`nameLen = len - 6`) in `0x0A` parsing so both 14-char and longer filenames parse cleanly.
   - Removed `.take(14)` string truncation in `BleManager.kt` (`downloadFile` and `probeDeleteCmds`).

## Verification Evidence
1. **File Detection & Download on Phone:**
   - Both `20260812102746.mp3` (2 min 30 sec) and `20260812113220.mp3` (2 min 42 sec) are verified complete (`isComplete=true`) with valid audio durations and present in Room database and disk.
2. **Unit Tests:**
   - `.\gradlew :app:testDebugUnitTest` passed cleanly (`BUILD SUCCESSFUL in 52s`).
3. **Git Hygiene:**
   - All changes committed and pushed to `origin/main`.
