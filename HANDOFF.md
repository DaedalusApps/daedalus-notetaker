# Handoff Brief — Daedalus Notetaker

Written 2026-08-12 (session 5). Replaces the previous version. Read this whole file before starting.

---

## Current state

- **`main`** = `9dd6936`, clean working tree, in sync with `origin/main`.
- **`.\gradlew :app:testDebugUnitTest` → 414 tests / 0 failures / 43 suites.**
- **Phone** (Galaxy S26 Ultra, `R3GL503MXPX`) is on the **release** build, **versionCode 294**.
- **Physical FW920 Unit Verified:** Tested live over BLE against physical FW920 unit (`fwName=xink_test`, battery=100%). Confirmed clean enumeration of all 16 hardware files (in 270ms) and streaming audio transfers over `B0B4`. Replaced fixed 5s list deadline with per-item 3s idle timeout in `BleManager.kt`.
- **Data integrity verified:** All 21 recordings (57,896,028 bytes) snapshotted before device testing and verified 100% MD5 byte-identical post-install.

**Merged this session (Session 5):**

| PR / Commit | Issue | What |
|---|---|---|
| #111 | #108 | Refresh device file list before re-download check; ignore non-FileList packets during enumeration |
| #112 | #102 | Wired `AnalysisForegroundService` lifecycle and status updates across BLE sync, re-download, and AI analysis |
| `1756492` | #108 | Replaced fixed 5s file list deadline with 3s per-item idle timeout in `BleManager.kt` after live hardware test discovery |

---

## The 6-pillar suite: status

| Pillar | Status | Detail |
|---|---|---|
| 1. Background Service | **Delivered & Wired (#102)** | `AnalysisForegroundService` started/updated/stopped during BLE sync, re-download, and AI analysis. Declared `FOREGROUND_SERVICE_DATA_SYNC` in manifest for API 34+ compliance. |
| 2. Calendar | **Delivered** | `TodoScreen.kt:302`. Device-verified: chooser launched, +191 ms |
| 3. Speed / Skip | **Delivered** | Speed state lives in `RecordingViewModel`. Device-verified |
| 4. Speaker Formatting | **Pending (#103)** | `SpeakerDiarizer` not yet wired to UI/pipeline |
| 5. FTS4 Search | **Pending (#101)** | No `RecordingFtsEntity` yet; pre-existing `LIKE` search active |
| 6. Storage Repair | **Deleted (#100)** | Re-download via BLE (#108 fixed) covers recovery non-destructively |

---

## Open issues & next session starting point

| # | Title | Note |
|---|---|---|
| **#103** | `SpeakerDiarizer` not wired to pipeline or UI | **Start here tomorrow.** Wire speaker badges in `NoteDetailScreen.kt` and integrate diarization into pipeline |
| #101 | FTS4 never implemented | Lowest priority at 21 recordings; `LIKE` search active |
| #104 | Exported `AdbReceiver` lets any app trigger hardware deletion | Debug builds only; release unaffected |
| #106 | Corruption detection has no real-data coverage | `realFileCrossCheck` skips silently when fixtures missing |

**#101, #102, #103 need an owner priority call before building.** They are new feature work, not
verification. Do not silently absorb them.

---

## Hard constraints — these are not negotiable

- **The phone holds real user recordings, frequently the only copy** (the FW920 source is often
  already deleted). Never `pm clear`, never `adb uninstall`, never backup-import over live data.
  **`adb install -r` only.**
- **Debug↔release swaps install in place** because release is signed with the **debug keystore**
  (`app/build.gradle.kts:49`). This is why no wipe is needed to get ADB hooks on the phone.
- **Installing an APK kills any running analysis.** Check the app is idle first
  (`adb shell "top -b -n 1 -q -o PID,%CPU | grep <pid>"`) — never install mid-run.
- **Finish every session with `assembleRelease` installed on the phone.**
- **Room uses WAL.** Pull `daedalus_notes.db-wal` alongside the db or you get a stale copy.
- **Use `adb exec-out`, not `adb shell`, to pull binaries on Windows.** `adb shell` applies CRLF
  translation and silently corrupts them — a db snapshot came back 1,412 bytes larger this session.
- **Capture logcat live** (start the capture *before* triggering). A post-hoc `adb logcat -d` has
  missed the audit line more than once.
- **Verify on device before claiming anything works.** If you catch yourself explaining why
  something *should* work, go test it instead.

### Device-test data-integrity protocol (owner instruction, restated in session)
> "anything you decide to test on my phone must make sure my data is intact"

1. Before any device work: `adb pull` all of `getExternalFilesDir(null)/Recordings/`, plus the db
   and `-wal`, and record MD5s.
2. Destructive or in-place-rewriting paths run against a **throwaway file you push**, never a real
   recording.
3. Re-pull and diff MD5s afterwards to *prove* byte-identity. Report it.

This session that baseline was 21 files / 57,896,028 bytes, verified byte-identical **five times**.
The scratchpad holding it is session-scoped and is gone — re-pull your own baseline.

---

## Facts established — do not re-derive

**BLE transport:**
- **Both download `Ack(0x0B)` packets arrive on B0B2** — measured on hardware 2026-08-12:
  `RX char=0000b0b2-… [12b]: A0 0A 01 0B 05 00 00 00 F4 D8 45 E1` (ready) and
  `RX char=0000b0b2-… [8b]: A0 0A 01 0B 01 02 70 CE` (EOF). `GEMINI.md` records this as confirmed.
- **B0B3 has never carried data**, including across a full connect → list → download → poll session.
  A one-shot `Log.w` now fires if it ever does. If you see it, investigate before trusting routing.
- **Notification sizes are NOT a usable loss signal.** Android coalesces notifications
  non-deterministically: the same lossless 62,680-byte file arrived as 362 / 361 / 355 / 360 / 359
  notifications across five transfers, all MD5-identical (`7eb5e3a9a1c8c886642e748c56f97727`).
  A cadence-based detector was built, unit-tested with 10 red-first tests, reviewed twice, and was
  still completely wrong. **Do not rebuild it.**
- **The `A0 0A` misparse rate is ~0.0026 per 10 MB**, not the 0.65 issue #96 claimed — it needs the
  prefix *and* a length match (~1 in 2^24). **#96 does not explain #94's 244-byte interior gaps.**

**Known-good reference file:** `20260806130549` — 62,680 bytes, MD5 `7eb5e3a9a1c8c886642e748c56f97727`,
`frameScan=436 frames, 0 gaps`. Use it for any transfer-integrity check.

**ffmpeg decode errors across the device corpus:** 0, 0, 0, 0, 6, 523. Only `20260804141258.mp3` is
badly damaged. Ground truth: `ffmpeg -v error -i FILE -f null -`.

**ADB triggers** (debug build only; app must be foregrounded). Single source of truth is
`AdbActions.kt`; a test enforces that handlers, the dynamic `IntentFilter`, and the manifest agree.
```
adb shell am broadcast -a com.daedalus.notes.SYNC -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.ANALYZE --es filename "20260812113220" -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.SET_SPEED --ef speed 1.5 -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.FORMAT_SPEAKER --es filename "20260812102746" -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.SEARCH_FTS --es query "initiative" -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a "com.daedalus.notes.ADD_CALENDAR" --es title 'Multi word title' -n com.daedalus.notes/.AdbReceiver
```
**Quote any extra containing spaces for the device-side shell** — an unquoted title was word-split
this session and `am` parsed a fragment as the package (`pkg=action`), so the broadcast never matched.

**DB keys have no extension for BLE-synced recordings** (`20260812102746`), but imports keep theirs
(`.mp3`, `.m4a`). Using the wrong form silently returns "no transcript".

---

## Working process

Load the **`apply-working-process`** skill first. Orchestrator delegates and never edits directly;
fresh equal-or-better reviewer on every diff; three gates (`/simplify` → `/security-review` →
`/code-review`) with every finding fixed; issues before branches; decisions logged to
`prd/DECISIONS.md` **the same session**. `prd/` is gitignored — read `DECISIONS.md` D22–D33 for the
full reasoning behind everything above.

**Budget warning.** This session cost **$113 for three PRs**; ~20 subagents, 94% of usage from
subagent fan-out, Sonnet reviewers alone $69. The owner's weekly limit hit 98%. Three levers the
owner asked to be applied to the skill:
1. **Cap review rounds at two** before surfacing to the owner. One branch here went four rounds and
   ended in the feature being deleted — rounds 2–4 were largely waste.
2. **Route mechanical checks to Haiku** (orphan imports, deletion completeness, stale comments).
   Reserve the top tier for adversarial passes on data-loss and security paths, where it genuinely
   earned its cost — it caught the repair engine and a test guard that a *comment* could satisfy.
3. **Skip the 4-agent simplify fan-out on small diffs**; use one combined cheap pass. Security and
   code review always run at full strength.

---

## Judgment lessons worth inheriting

**A measurement against real data beats a reviewer's model.** A reviewer flagged a trailing-tag
false positive; the implementer measured it against all 19 real recordings and found **zero** effect;
the orchestrator ordered the fix anyway. That fix introduced an unbounded classifier under which a
recording that lost all but 72 ms would display as *undamaged*. Logged as D30.

**Green tests say nothing about integration.** All six pillars had passing tests. Three were not
connected to anything.

**Deleting a feature is a legitimate engineering answer.** The repair engine failed two adversarial
reviews and, even working correctly, destroyed ~52 s of valid audio on the worst file. `redownload`
already covered the real need non-destructively.
