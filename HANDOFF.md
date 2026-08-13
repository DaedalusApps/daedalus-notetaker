# Handoff Brief — Daedalus Notetaker

Written 2026-08-12 (session 4). Replaces the previous version, which claimed three features as
delivered that do not exist. Read this whole file before starting.

---

## Current state

- **`main`** = `a126fa0`, clean working tree, in sync with `origin/main`.
- **`.\gradlew :app:testDebugUnitTest` → 412 tests / 0 failures / 43 suites.**
- **Phone** (Galaxy S26 Ultra, `R3GL503MXPX`) is on the **release** build, **versionCode 294**.
- `.git/security-review-ok` records `a126fa0`.
- The FW920 recorder was powered on and connected during this session's hardware tests.

**Merged this session** — three PRs, each red-first, three gates, CI green, device-verified:

| PR | Issue | What |
|---|---|---|
| #105 | #99 | Single source of truth for debug ADB triggers |
| #107 | #96 | BLE notifications routed by characteristic, not packet prefix |
| #109 | #100 | `AudioRepairEngine` deleted; `Mp3FrameScan` corrected |

---

## The 6-pillar suite: what is actually true

The previous handoff listed all six as delivered and "verified". They were verified only by unit
tests that exercised helper classes **never wired to anything**. Audited against code at `c136a0c`:

| Pillar | Claimed | Reality |
|---|---|---|
| 1. Background Service | Bound during sync and `doAnalyzeExclusive` | **Dead code.** `AnalysisForegroundService` is in the manifest and has `start`/`stop` helpers, but nothing in the app calls them. Analysis still dies when the app is backgrounded. → **#102** |
| 2. Calendar | Button on To-Do cards | **Real.** `TodoScreen.kt:302`. Device-verified: chooser launched, +191 ms |
| 3. Speed / Skip | Speed toggles + ±10s | **Real.** Speed state now lives in `RecordingViewModel` (per plan spec). Device-verified, clamp works |
| 4. Speaker Formatting | Badges rendered in `NoteDetailScreen` | **Not wired.** Zero occurrences of "Speaker" in that file. `formatTranscript` has one caller: the debug `FORMAT_SPEAKER` trigger. → **#103** |
| 5. FTS4 Search | "Verified search query flow" | **Never built.** No `RecordingFtsEntity`, no `MATCH` query anywhere. Only the pre-existing `LIKE` search. → **#101** |
| 6. Storage Repair | Scans, trims, repairs | **Deleted.** It truncated recordings to everything before the first gap and overwrote the only copy with no backup. → see below |

**Do not trust a completion claim in a doc without checking the code.** That is the single most
expensive lesson of this session.

---

## Open issues

| # | Title | Note |
|---|---|---|
| **#108** | Re-fetch falsely reports "no longer on device" | **Start here.** Cheap, and it undermines the recovery path that made deleting the repair engine safe |
| #102 | `AnalysisForegroundService` never started | Highest user impact of the three unbuilt pillars — long analyses still die when backgrounded |
| #103 | `SpeakerDiarizer` not wired to pipeline or UI | Has a design question to settle first: store diarized text (affects embeddings/Ask/Gemma prompt) or render-time only |
| #101 | FTS4 never implemented | Lowest value at 21 recordings; `LIKE` may be adequate. Owner has not decided whether to build it |
| #104 | Exported `AdbReceiver` lets any app trigger hardware deletion | Debug builds only; release unaffected |
| #106 | Corruption detection has no real-data coverage | `realFileCrossCheck` skips **silently** — a skipped test reporting as passing is how this stayed invisible |

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
