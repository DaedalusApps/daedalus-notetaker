# Handoff Brief — Daedalus Notetaker

## Goal
Fix BLE synchronization for recording `20260812102746.mp3` (`Note-20260812102746`) and maintain hardened state.

## Status & Branches
- **`main`**: Clean working tree, up-to-date with `origin/main`.
- **Latest Commit**: `fix(ble): allow control responses despite hardware CRC variations` (`33897c3`).

## Key Root Causes & Fixes Solved
1. **CRC Validation Fallthrough Bug in `FW920Protocol.kt`:**
   - Command response packets (`0xA0 0x0A ...`) sent by physical FW920 hardware failed strict CRC-16 checks due to hardware-side calculation differences.
   - Line 156 dropped valid `0xA0 0x0A` response packets as `AudioChunk`, causing `collectFileList()` to reject all 13 file entry notifications from the recorder and report `0 files`.
   - **Fix:** Removed strict CRC dropping on `0xA0 0x0A` packets with valid header and payload length.

2. **Dynamic Filename Parsing:**
   - Calculated filename length dynamically (`nameLen = len - 6`) in `0x0A` parsing so both 14-char and longer filenames parse cleanly.
   - Removed `.take(14)` string truncation in `BleManager.kt` (`downloadFile` and `probeDeleteCmds`).

## Verification Evidence
1. **File Detection & Download on Phone:**
   - File `20260812102746.mp3` verified created and downloading at `/sdcard/Android/data/com.daedalus.notes/files/Recordings/20260812102746.mp3`.
2. **Unit Tests:**
   - `.\gradlew :app:testDebugUnitTest` passed cleanly (`BUILD SUCCESSFUL in 55s`).
3. **Git Hygiene:**
   - All changes committed and pushed to `origin/main`.
