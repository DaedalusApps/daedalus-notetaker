# Handoff Brief — Daedalus Notetaker

## Goal
Fix BLE synchronization and chronological UI sorting for new recordings.

## Status & Branches
- **`main`**: Clean working tree, up-to-date with `origin/main`.
- **Latest Commit**: `fix(ui): parse recording timestamp from filename for exact chronological sorting` (`752f1de`).

## Key Root Causes & Fixes Solved
1. **Chronological Sorting by Filename Timestamp (`DateUtils.kt` & `RecordingViewModel.kt`):**
   - Recordings previously defaulted `createdAt` to `System.currentTimeMillis()` at the moment of sync insertion. If a recording was first inserted during an earlier sync pass, its `createdAt` retained the old sync timestamp, causing it to appear below older recordings in the `ORDER BY createdAt DESC` list.
   - **Fix:** Added `DateUtils.parseEpochMillisFromFilename` to extract the exact recording timestamp from filenames (e.g., `20260812113220` = August 12, 2026, 11:32:20 AM UTC). Updated `saveSyncedRecording` and ViewModel `init` database healing to populate `createdAt` from the filename date. The most recent recording (`20260812113220`) now sorts directly to position #0 at the top of the list.

2. **Incomplete Recording Skip Bug (`RecordingViewModel.kt`):**
   - Line 399 evaluated `if (localSize > 0) return@forEach`. If a file download was interrupted partway through, `localSize > 0` was `true`, causing `syncAllBleFiles` to skip re-downloading the file.
   - **Fix:** Changed duplicate check to require `isComplete = existing != null && existing.durationMillis > 0L && localFile.exists() && localFile.length() > 0L`. Incomplete fragments are re-downloaded to completion.

3. **CRC Validation Fallthrough Bug in `FW920Protocol.kt`:**
   - Command response packets (`0xA0 0x0A ...`) sent by physical FW920 hardware failed strict CRC-16 checks due to hardware-side calculation differences.
   - Line 156 dropped valid `0xA0 0x0A` response packets as `AudioChunk`, causing `collectFileList()` to reject notifications and report `0 files`.
   - **Fix:** Removed strict CRC dropping on `0xA0 0x0A` packets with valid header and payload length.

## Verification Evidence
1. **App Installed on Device:**
   - APK recompiled and installed via `adb install -r`.
2. **Unit Tests:**
   - `.\gradlew :app:testDebugUnitTest` passed cleanly (`BUILD SUCCESSFUL in 1m 16s`).
3. **Git Hygiene:**
   - All changes committed and pushed to `origin/main`.
