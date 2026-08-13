# Handoff Brief — Daedalus Notetaker

Written 2026-08-13 (session 6). Replaces the previous version. Read this whole file before starting.

---

## Current state

- **`main`** = `5a60965`, clean working tree, in sync with `origin/main`.
- **`.\gradlew :app:testDebugUnitTest` → 419 tests / 0 failures / 1 skipped.** The skip is
  `Mp3FrameScanTest.realFileCrossCheck` and it is **by design** — see #106 below. A skip here is
  correct; a *pass* would mean something regressed.
- **`:app:assembleRelease` builds clean** (`lintVitalRelease` passes).

> **⚠ THE PHONE IS STALE. No device work happened this session — no device was attached
> (`adb devices` empty), and the owner scoped the session to work that needs no ADB.** The phone is
> still on the **session-5 release build, versionCode 294**. Everything merged in session 6 is on
> `main` and **not on the phone**. The next session that touches hardware must install first.
>
> The last recorded device state, from session 5, was: Galaxy S26 Ultra `R3GL503MXPX`, 21 recordings
> / 57,896,028 bytes, MD5-verified byte-identical. **That baseline is from a previous session — do
> not trust it. Re-pull your own before touching anything** (see the data-integrity protocol below).

**Merged this session (Session 6):**

| PR | Issue | What |
|---|---|---|
| #113 | #104 | Removed the `.AdbReceiver` `<intent-filter>` and switched MainActivity's dynamic receiver to `RECEIVER_NOT_EXPORTED`, closing both *implicit*-broadcast paths into the debug ADB harness |
| #114 | #106 | `realFileCrossCheck` now reports SKIPPED instead of a false pass; incomplete fixture dirs hard-fail; `expectedResults.size` pinned so the list cannot be silently emptied |

**Both PRs used `Refs`, not `Closes`. #104 and #106 are still OPEN on purpose** — each has an
acceptance criterion that is device-blocked. What shipped is narrower than either issue title, and
both PR bodies say so explicitly. Do not read "merged" as "done".

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
| **#103** | `SpeakerDiarizer` not wired to pipeline or UI | **Start here.** Wire speaker badges in `NoteDetailScreen.kt` and integrate diarization into the pipeline. Needs a device to verify |
| #104 | Exported `AdbReceiver` — **residual only** | Implicit paths closed in #113. Remaining: an explicit-component broadcast still reaches it on debug builds. Needs the phone |
| #106 | Corruption detection — **residual only** | Silent-skip fixed in #114. Remaining: the trailing-span paths still have no real-data coverage. Needs captured damaged transfers |
| #101 | FTS4 never implemented | Lowest priority at 21 recordings; `LIKE` search active |

**#101 and #103 need an owner priority call before building.** They are new feature work, not
verification. Do not silently absorb them.

### What is actually left on #104 and #106

Both are one device session away from closing. Neither needs new design work.

**#104 residual.** `.AdbReceiver` must stay `android:exported="true"` for adb shell (uid 2000), so a
third-party app on a *debug* build can still deliver an explicit-component broadcast:

```kotlin
sendBroadcast(Intent("com.daedalus.notes.DELETE_FILE")
    .setComponent(ComponentName("com.daedalus.notes", "com.daedalus.notes.AdbReceiver"))
    .putExtra("filename", "20260812113220"))
```

`RECEIVER_NOT_EXPORTED` does not block it — the attacker goes *through* `AdbReceiver`, and our own
forward code sets `_forwarded=true`. Two independent reviews confirmed this is now the **only**
remaining path. The candidate fix is `android:permission` naming a permission the shell uid holds
but a normal app cannot obtain; **whether adb can still reach the receiver under it is exactly what
needs testing on the phone.** Scope in the unsanitized `filename` at the same time: it reaches
`BleManager.deleteFile` (`BleManager.kt:688`) with no `SafeFilename` call, though `SafeFilename.kt:5`
names `am broadcast` extras as attacker-influenced.

*Mitigator:* the dynamic receiver only exists while `MainActivity` is alive (`onCreate` registers,
`onDestroy` unregisters at `MainActivity.kt:252`). The manifest receiver cold-starts the process but
the forward then lands on nothing, so the attack needs the app already running.

**#106 residual.** `recordGap`-on-unresyncable-EOF, `isBenignTrailer` and `isBoundedApeV2Footer` are
exercised **only** by synthetic fixtures authored alongside the parser. No file in the real corpus
takes those paths. Closing it needs genuinely damaged real captures — deliberately interrupted BLE
downloads, with ffmpeg ground truth.

**Constraint to solve first: this repo is PUBLIC and the corpus is real meeting audio, so fixtures
can never be committed here.** The option recorded for that session is **structure-only fixtures** —
keep the MP3 frame headers, zero the audio payload. The structural damage the scanner reasons about
survives; nothing intelligible leaks. Unlike synthetic fixtures, the structure comes from a real
damaged transfer rather than from the parser's own model.

**Do not "fix" #106 by adding synthetic fixtures.** That was considered and rejected by the owner —
see D35 and the reasoning under *Judgment lessons*.

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

**⚠ FIRST THING TO CHECK IF THE ADB HARNESS STOPS RESPONDING AFTER THE NEXT DEBUG INSTALL.**
PR #113 switched MainActivity's dynamic receiver to `RECEIVER_NOT_EXPORTED`. `reverse/WIFI_DISCOVERY.md`
previously recorded that constant as having been *tried and abandoned* because the harness broke. The
security review established that note misattributed its own cause: at `0c58f51` the in-package
forwarder already existed, and the real blocker was the manifest receiver being `exported="false"` at
the time, which flipped to `"true"` later in `7a13697`. Each leg of the current path is exercised on
hardware; **the exact combination (manifest `exported="true"` + dynamic `RECEIVER_NOT_EXPORTED`) has
never been run on the phone.** If triggers go silent, revert that one constant first and report it —
it is the highest-prior suspect and the whole harness depends on it.

**ADB triggers** (debug build only; app must be foregrounded). Single source of truth is
`AdbActions.kt`; a test enforces that handlers and the dynamic `IntentFilter` agree. **Every
invocation must use the explicit `-n com.daedalus.notes/.AdbReceiver` form** — the manifest
intent-filter is gone as of #113, so a bare `-a ACTION` broadcast now reaches nothing.
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

**Budget.** Session 5's three levers are now baked into the installed `apply-working-process` skill
itself (verified identical to `~/projects/fable-quality-library` HEAD at session-6 start), so they no
longer need restating each session — load the skill and follow it.

Session 6 applied them and cost roughly **a third of session 5 for two PRs**: 8 subagents total
across both, one Haiku simplify pass per diff instead of a 4-agent fan-out, and exactly **one fix
round per PR** — the cap was never approached. The top tier was spent once, on #104's adversarial
security pass, and it earned it: it was the agent that caught the misattributed
`RECEIVER_EXPORTED` history by walking `git log -p` instead of accepting the narrative. Sonnet
handled both reviews on #106, correctly — that diff touches no data path.

**The routing rule that mattered:** tier by what the check is *verifying*, not by the fact that it is
a review. #106 is test infrastructure; a top-tier adversarial pass on it would have bought nothing.

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

**An issue's stated scope is a hypothesis about the bug, not a boundary on it.** #104 described one
hole: the manifest `<intent-filter>`. There were two, and the undocumented one was worse — the
dynamic receiver was registered `RECEIVER_EXPORTED` over all 14 actions, so any app could reach
hardware deletion with `setPackage("com.daedalus.notes")` plus a spoofed `_forwarded=true`, never
touching `.AdbReceiver` at all. Fixing only what the issue described would have closed the issue
while leaving the data-loss path open. Read the surrounding code before accepting the framing.

**Check the history before building on a claim about it.** The stated rationale for #104 — that the
`RECEIVER_EXPORTED` note predated the #99 forwarder — was wrong, and it was the entire justification
for the change. `git log -p` on the manifest disproved it in one command. This is the second session
running where a confident causal story about this codebase did not survive contact with the actual
history. Cheap check, high yield.

**Half a fix reads exactly like a whole one.** #106's first cut made a silently-skipped test report
SKIPPED — but emptying `expectedResults` would still have made it *pass* having validated nothing:
`missingFiles` vacuously empty, loop runs zero times, `checked == 0 == expectedResults.size`. A fix
for "this test can lie about having run" that still lets the test lie about having run. When you fix
a class of bug, enumerate the other routes into it before calling it closed.

**Prose is part of the diff, and it outlives the code.** Code review's sharpest finding on #113 was
not a code defect: the new tests and comments were written as though the security hole were fully
closed, when it was deliberately only narrowed. Three tests would have sat there pinning an
incomplete fix behind confident language. **Tests pin behaviour; comments pin what the next person
believes.** Both were narrowed to the true claim.

**Ship with the issue open when the issue isn't done.** Both PRs used `Refs`, not `Closes`, and
named their unmet acceptance criteria. Given D33 — six pillars reported delivered, three of which
did not exist — a PR that states what it does *not* do is the cheapest defence available.
