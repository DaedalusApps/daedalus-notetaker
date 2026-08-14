# Handoff Brief — Daedalus Notetaker

Written 2026-08-14, closing session 6 (which included a live-device phase). Replaces the previous
version. Read this whole file before starting — it is written to be the only context you need.

---

## Current state

- **`main`** = `7968b62`, clean working tree, in sync with `origin/main`, **green on CI**.
- **`.\gradlew :app:testDebugUnitTest` → 474 tests / 0 failures / 1 skipped.**
  The skip is `Mp3FrameScanTest.realFileCrossCheck` and it is **by design**. A skip there is
  correct; a *pass* would mean something regressed.
- **`:app:assembleRelease` builds clean** (`lintVitalRelease` passes) at versionCode 334.
- **Phone** (Galaxy S26 Ultra, `R3GL503MXPX`) is on the **release** build, **versionCode 313**,
  installed with `adb install -r`. `versionCode = gitCommitCount` (`app/build.gradle.kts:41`); `main`
  is now well ahead of that.

> **⚠ THE NEXT INSTALL MIGRATES THE DATABASE. This is the one irreversible thing waiting.**
> #101 took the schema from **12 to 13**, adding an FTS4 index. `MIGRATION_12_13` runs once, on
> first launch after install, against the owner's real database. It has been exercised only against
> a synthetic v12 DB via `MigrationTestHelper`, **never against the actual 39-row database.**
>
> Why it should be safe, and what to check anyway:
> - Android wraps `onUpgrade` in a transaction, so a failure rolls back to v12 with data intact.
> - The migration's `CREATE VIRTUAL TABLE` was verified byte-identical to Room's generated schema,
>   so Room's identity check cannot throw on open (that failure mode would make the app unopenable).
> - The back-fill is a pure `INSERT … SELECT`; no row crosses into the JVM heap, so no OOM.
>
> **After the next install: open the app, confirm it launches, and search for a word you know is in
> an old transcript.** If search returns nothing for pre-existing recordings, the back-fill did not
> run — that is the failure this needs watching for. Pull the db first, per the protocol below.

- **Data verified: 22 recordings, 58,658,554 bytes, MD5 byte-identical at seven checkpoints** —
  before any work, after each of five installs, and after every interrupted transfer. Baseline was
  pulled with `adb pull` and proven identical to the device with per-file MD5 comparison.
  The db, `-wal` and `-shm` were also pulled byte-exact via `adb exec-out`.

> **One file on the phone is NOT the owner's data:** `20260813084543.mp3` (337,148 bytes, MD5
> `a0e25951817b9161008bd0a8b9aae194`) and its matching FW920 entry are a throwaway test recording
> made this session. Safe to delete. It is a useful reproduction asset for #119.

**Merged this session (Session 6):**

| PR | Issue | What |
|---|---|---|
| #113 | #104 | Removed the `.AdbReceiver` `<intent-filter>` and switched the dynamic receiver to `RECEIVER_NOT_EXPORTED`, closing both *implicit*-broadcast paths into the debug ADB harness |
| #114 | #106 | `realFileCrossCheck` reports SKIPPED instead of a false pass; incomplete fixture dirs hard-fail; `expectedResults.size` pinned so the list cannot be silently emptied |
| #115 | — | Handoff |
| #118 | **#104 closed** | `.AdbReceiver` gated on `android.permission.DUMP`; `SafeFilename` guards on all three destructive ADB handlers; dot-only names rejected centrally |
| #120 | #106 | Real interrupted-BLE-transfer fixtures (structure-only, audio zeroed), pinned against ffmpeg ground truth |
| #121 | — | Handoff |
| #123 | #119 | Re-download that comes back shorter than the copy it replaced is rejected and the backup restored; two latent data-loss defects in `restoreBackup` fixed |
| #124 | **#117 closed** | Zero-byte transfer no longer leaves a 0-byte file; failed downloads are logged, counted and surfaced |
| #127 | **#101 closed** | FTS4 search, schema 12→13 with back-fill; hand-written sync triggers removed in favour of Room's |
| #128 | — | Removed stray scratch files swept into #127 by a broad `git add -A` |
| #129, #133, #135 | — | Handoff |
| #130 | #125 | `@Upsert` for rowid stability (issue re-scoped — see below) |
| #131 | **#126 closed** | 13→12 downgrade migration, so an older APK opens instead of throwing |
| #132 | **#122 closed** | `heavyWork` widened to the whole re-download critical section |
| #134 | **#103 closed** | Transcripts render as paragraphs; the speaker fiction is deleted |

**Closed in session 6: #104, #117, #101, #126, #122, #103.** #106 was closed by the owner.
**#119 stays open** — its guard shipped, but the root cause of the short transfer is unexplained and
needs hardware. **#125 stays open and was re-scoped** — its stated mechanism does not exist (see
below). **#116, #136** were filed and remain open.

### #103 is CLOSED — shipped as paragraph formatting (PR #134)

`SpeakerDiarizer` is deleted. It never detected speakers: it flipped the label every three
sentences with no audio input at all. What shipped is `TranscriptFormatter.formatParagraphs`, which
groups sentences into paragraphs with **no speaker labels**, applied at **display time only** —
the stored transcript is untouched, so FTS search and markdown export still read exactly what
Whisper produced.

One thing worth knowing if you touch the transcript view: `highlightMatches` is now
**whitespace-insensitive**, via a two-pointer scan. It has to be — search matches the raw stored
text, but highlighting runs over the reformatted text where every third sentence boundary became a
blank line, so a query spanning one of those matched globally and then silently failed to highlight
in the note. **Do not "simplify" it back to `indexOf`.**

The reasoning below is kept because the lesson generalises.

### Why #103 was refused as originally scoped

`SpeakerDiarizer.formatTranscript` does not detect speakers. It splits on sentence boundaries and
flips between "Speaker 1" and "Speaker 2" **every three sentences, unconditionally** — there is no
audio analysis, no embedding, no turn-taking signal, and the function never sees the audio at all,
only the transcript string. On a solo memo it invents a second participant; on a four-person meeting
it collapses everyone into two; wherever a real speaker change happens it is right only by
coincidence.

Wiring it into `NoteDetailScreen` as speaker badges would have presented fabricated attribution as
fact in the owner's own meeting notes. The owner was given three options — drop the speaker claim and
ship it as paragraph formatting; do real audio-based diarization; or delete it as #100 did with
`AudioRepairEngine` — and chose the first.

Its unit tests passed and always did: they pinned the formatting mechanics and *could not fail for
the reason that mattered*. Same shape as D30's cadence detector.

**The generalisable lesson: refuse the false part, deliver the true part.** The literal ask ("wire it
up") would have shipped a lie. Refusing it was right, but refusing is not the same as delivering
nothing — the component did one genuinely useful thing, and shipping exactly that, with the false
claim removed and the name corrected, closed the pillar honestly.

---

## The 6-pillar suite: status

| Pillar | Status | Detail |
|---|---|---|
| 1. Background Service | **Delivered & Wired (#102)** | `AnalysisForegroundService` started/updated/stopped during BLE sync, re-download, and AI analysis. Declared `FOREGROUND_SERVICE_DATA_SYNC` in manifest for API 34+ compliance. |
| 2. Calendar | **Delivered** | `TodoScreen.kt:302`. Device-verified: chooser launched, +191 ms |
| 3. Speed / Skip | **Delivered** | Speed state lives in `RecordingViewModel`. Device-verified |
| 4. Speaker Formatting | **Re-scoped & delivered (#103)** | Shipped as paragraph formatting, not speaker attribution. `SpeakerDiarizer` deleted; `TranscriptFormatter` renders paragraphs at display time |
| 5. FTS4 Search | **Delivered (#101)** | `RecordingFts` (`@Fts4(contentEntity)`), schema 12→13 with back-fill. **Migration has never run on the real DB** — see the warning at the top |
| 6. Storage Repair | **Deleted (#100)** | Re-download via BLE (#108 fixed) covers recovery non-destructively |

---

## Open issues & next session starting point

| # | Title | Note |
|---|---|---|
| **#119** | Root cause: why a transfer after an interrupted one comes back short | **Start here.** The guard shipped; the cause is unexplained. Needs a clean-start hardware repro |
| #125 | **Re-scoped.** Its stated mechanism does not exist — see below | Needs a device read of the live write-connection pragma and trigger list |
| #116 | Delete/download packets omit the 14-byte filename clamp | Needs a throwaway-file hardware delete to verify |
| #136 | `RecordingViewModelTest` intermittently red on Linux CI (`UncaughtExceptionsBeforeTest`) | **The only issue that does NOT need the phone.** See below |

### Where to start

**#136 is the only issue that does not need the phone**, and it is the one that will bite CI. The
rest (#116, #119, #125) all require hardware.

### #136 — the CI flake, and the concrete lead

Symptom, seen on a **documentation-only** PR so the change cannot have caused it:

```
RecordingViewModelTest > splitAnalysis_cleanFourOfFourParts_shortSummaryUnchanged FAILED
    kotlinx.coroutines.test.UncaughtExceptionsBeforeTest: There were uncaught exceptions
    before the test started.
```

`UncaughtExceptionsBeforeTest` **does not indicate a fault in the test it names.** A coroutine from
an *earlier* test escaped onto a real background thread, threw after that test finished, and the
exception was attributed to whichever test started next. The named test is the victim, so it will
appear to move between runs.

**The cause is almost certainly raw `Dispatchers.IO` in `RecordingViewModel`.** This mechanism was
established precisely during the #119 work: production code using raw `Dispatchers.IO` inside a
method under test does not synchronise with `advanceUntilIdle()` under `StandardTestDispatcher`, so
the coroutine continues on a real thread after the test asserted and returned. `redownloadAndAnalyze`
and `restoreBackup` were converted to the injectable `ioDispatcher`; **several methods were not** —
roughly the `init` block, `fullAutoSync`, `syncFiles`, `doAnalyzeExclusive`, `exportNote`,
`exportLibraryAnswer`.

**Start with `doAnalyzeExclusive`** — the failing test is on the split-analysis path.

Reproduce deliberately, not by luck: a single green run proves nothing for an intermittent. Establish
a failure rate in CI before and after (CI is the only Linux available; see the constraint below).

### #125 was filed on a mechanism that does not exist — read before acting on it

I filed #125 claiming `INSERT OR REPLACE` orphaned FTS entries because `recursive_triggers`
defaults off, so the implicit delete fired no sync trigger. **That is wrong.**
`androidx.room:room-runtime:2.6.1`'s `InvalidationTracker.internalInit` unconditionally executes

```
PRAGMA temp_store = MEMORY;
PRAGMA recursive_triggers='ON';
```

on **every** database open, on **every** platform, before any DAO method is reachable. The literal
string is in `InvalidationTracker.class`; I verified it directly. The raw SQLite default *is* 0 on
both Robolectric and Android — Room forces it to 1 on both. **There is no platform divergence.**

With the pragma ON, REPLACE's implicit delete fires the sync trigger and the index does not orphan.
`integrity-check` passes at 1 and fails at 0. The test that appeared to prove corruption only did so
because it pinned `recursive_triggers=0` in `setUp`, undoing what Room does — that pin is gone.

`@Upsert` still shipped (PR #130), justified honestly: stable rowid, roughly half the write cost, and
index integrity no longer contingent on an undocumented Room detail that the 2.7/KMP rewrite could
change. **Not** a fix for observed corruption.

I also had the severity backwards. #125 said "rowids are never reused" — false, there is no
`AUTOINCREMENT`, so rowid is `max+1` over current rows and deleting the top row frees it. A reviewer
reproduced one recording's transcript surfacing under another recording's name.

**PR #127's merged commit message repeats the same "defaults off" error.** History was not rewritten;
the correction lives here, on #125, and in D38/D39.

**#101 and #103 need an owner priority call before building.** They are new feature work, not
verification. Do not silently absorb them.

### #119 is the one that matters

Same FW920 file, transferred repeatedly. One transfer returned **217,412 bytes** where every other
attempt returned **337,148** — and it **completed normally**: end-of-file `Ack(0x0B)`,
`readyReceived=true`, `downloadFile: done`, no timeout, no error. Three subsequent clean runs were
byte-perfect and MD5-identical, so ordinary syncing is not lossy.

**Honest caveat: it was induced.** It happened immediately after several force-stops mid-transfer,
so the FW920 was very likely left with a stale stream position. It has NOT been reproduced from a
clean start.

Why it still matters: **re-download is the recovery path.** #100 deleted `AudioRepairEngine`
precisely because `redownload` covered the need non-destructively (D30). That path runs exactly when
a previous transfer went wrong — the state that produced the truncation here. And
`redownloadAndAnalyze` **deletes the local file before the replacement is known to be good**. If the
FW920 copy is later deleted, the shortfall is permanent and silent.

There is no end-to-end integrity check on a transfer. The device's `sizeBytes` is unusable for it
(wrong unit; it reported `33301509` for a 337,148-byte file and a 16 MiB placeholder for an empty
entry), so the EOF ack is currently treated as proof of completeness — and this shows it is not. The
corruption scanner would not catch it either: a clean truncation at a frame boundary leaves no
interior gap.

Reproduction asset: the throwaway `20260813084543` on both the FW920 and the phone. No personal
content — use it freely.

### #106's residual, and why more captures will not close it

`recordGap`-on-unresyncable-EOF, `isBenignTrailer` and `isBoundedApeV2Footer` are still exercised
**only** by synthetic fixtures. #120 added real-capture coverage, but branch tracing against an
instrumented build shows those captures only hit `recordGap` with a **non-null** resync position
(mid-stream loss), 28 and 70 times.

**An interrupted BLE transfer truncates cleanly at a frame boundary — it does not garble to EOF.**
So capturing more interrupted transfers cannot reach the trailing-span paths, however many you take.
That needs a different failure mode: damage continuing to EOF without resyncing. The issue's own
proposed remedy does not reach its own acceptance criterion. Recorded here because it is easy to
rediscover the hard way.

**Do not "fix" this by adding synthetic fixtures.** Considered and rejected by the owner — see D35.

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

Session 6's baseline was **22 files / 58,658,554 bytes**, verified byte-identical **seven times**.
The scratchpad holding it is session-scoped and is gone — re-pull your own baseline.

### Device-work gotchas that cost real time this session

- **Git Bash mangles device paths.** `adb pull /sdcard/...` becomes
  `C:/Program Files/Git/sdcard/...` and fails. Export `MSYS_NO_PATHCONV=1` for every adb command
  that names a device path.
- **`adb pull` on a directory is binary-safe** and is the right tool for the recordings; the
  `adb shell` CRLF warning applies to `adb shell cat`. Use `adb exec-out` for the db/wal/shm.
- **Android `md5sum` and Windows `md5sum` format differently** — two spaces vs ` *` (binary marker).
  A naive `diff` of the two reports all 22 files as changed when nothing has. Normalise with
  `sed -E 's/[ ]+\*?/  /'` before comparing, or you will scare yourself badly.
- **Do not sum sizes from `ls -la`** — it includes the `total` line and `.`/`..`. The per-file MD5
  comparison is the authoritative check; a byte total computed that way is off by a few KB.
- **The BLE log tag is `BleManager`, not `DaedalusBLE`.** Filtering on the wrong one makes a
  perfectly healthy transfer look completely silent. Real tags: `BleManager`, `DaedalusSync`,
  `DaedalusADB`, `DaedalusAI`, `AudioRecorder`.
- **`"Downloading …"` is a StateFlow update, not a log line.** Its absence from logcat means
  nothing.
- **The phone-mic path records AAC/`.m4a`** (`AudioRecorder.kt:36-37`), not MP3. It cannot produce
  fixtures for `Mp3FrameScan`, which only sees MP3s arriving over BLE.

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

**The `RECEIVER_NOT_EXPORTED` risk flagged in the previous handoff is RETIRED — verified on
hardware.** The full chain works: `-n` → exported manifest receiver → in-package forward → dynamic
`RECEIVER_NOT_EXPORTED` receiver → handler. Confirmed by logcat, repeatedly. The old
`reverse/WIFI_DISCOVERY.md` note that recorded this constant as tried-and-abandoned had
misattributed its own cause (the real blocker was the manifest receiver being `exported="false"` at
the time, flipped to `"true"` in `7a13697`); that note is corrected.

**Also verified: all three implicit attack forms now reach nothing** — bare `-a`, bare `-a` with a
spoofed `_forwarded=true`, and `-p com.daedalus.notes` with a spoofed `_forwarded=true`. The last of
those was a *live hole* before #113.

**ADB triggers** (debug build only; app must be foregrounded). Single source of truth is
`AdbActions.kt`; a test enforces that handlers and the dynamic `IntentFilter` agree.

- **Every invocation must use the explicit `-n com.daedalus.notes/.AdbReceiver` form.** The manifest
  intent-filter is gone as of #113, so a bare `-a ACTION` broadcast reaches nothing.
- **The receiver now requires `android.permission.DUMP` from the sender** (#118). `adb shell` holds
  it, so the harness is unaffected. Any other sender is refused.
- **`am` prints `Broadcast completed: result=0` whether the broadcast was delivered OR refused.**
  The exit code is worthless here. Only live logcat tells you which happened — this is the single
  most useful thing learned this session about testing the harness.

> **⚠ `SYNC` IS NOT A READ-ONLY TRIGGER.** `syncAllBleFiles` (`RecordingViewModel.kt:379-392`)
> processes queued deletions *before* it syncs: for every row with `pendingDelete=1` it calls
> `bleManager.deleteFile(...)`, wiping the file off FW920 hardware, then deletes the DB row. **The
> app also auto-syncs on BLE connect**, so merely launching it with the device in range can fire
> those deletions. It was safe this session only because the queue was empty. **Check
> `SELECT COUNT(*) FROM recordings WHERE pendingDelete=1` on a pulled copy of the db before letting
> the app connect.**
```
adb shell am broadcast -a com.daedalus.notes.SYNC -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.ANALYZE --es filename "20260812113220" -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.SET_SPEED --ef speed 1.5 -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.FORMAT_PARAGRAPHS --es filename "20260812102746" -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.SEARCH_FTS --es query "initiative" -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a "com.daedalus.notes.ADD_CALENDAR" --es title 'Multi word title' -n com.daedalus.notes/.AdbReceiver
```
**Quote any extra containing spaces for the device-side shell** — an unquoted title was word-split
this session and `am` parsed a fragment as the package (`pkg=action`), so the broadcast never matched.

**DB keys have no extension for BLE-synced recordings** (`20260812102746`), but imports keep theirs
(`.mp3`, `.m4a`). Using the wrong form silently returns "no transcript".

---

## Working process

Load the **`apply-working-process`** skill first — and **sync it from
`~/projects/fable-quality-library/skills/apply-working-process/` before loading**, since the owner
maintains it there and the installed copy can lag. Orchestrator delegates and never edits directly;
fresh equal-or-better reviewer on every diff; three gates (`/simplify` → `/security-review` →
`/code-review`) with every finding fixed; issues before branches; decisions logged to
`prd/DECISIONS.md` **the same session**. `prd/` is gitignored — read `DECISIONS.md` D22–D40 for the
full reasoning behind everything above.

### Two operational traps that cost time in session 6

**Check the CI *conclusion*, not just that it stopped being pending.** A merge-wait loop of the form
"poll until the output no longer says `pending`, then merge" will happily merge a **failed** build.
That happened once (harmlessly — docs-only change, flaky test, `main` stayed green), but it was wrong
every time it ran. Gate on `pass`/`success` explicitly.

**Write `.git/security-review-ok` as its own command.** The push gate reads the marker file, and
chaining `git rev-parse HEAD > .git/security-review-ok && git push` fails — the gate sees the old
value. Same for `git add && git commit <<heredoc && ...`, which silently did not commit at all once.
Run them as separate invocations and verify with `git log --oneline -1`.

**CI is the only Linux available.** Development is Windows; `.github/workflows/ci.yml` on
`ubuntu-latest` is the sole way to see Linux behaviour. Iteration is ~2.5 minutes and whole-suite
only, with no debugger and no local reproduction. `gh run rerun <id>` re-runs the *same commit*,
which is the right tool for characterising a flake. Because that window is so narrow, `testOptions`
now enables **full exception output** in test logging — a Linux-only failure used to print only
`AssertionError at Foo.kt:840` with no message, which made it undiagnosable. Do not remove that.

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

**Review the justification, not just the diff.** Four times in session 6 adversarial review
overturned a *premise* rather than finding a bug: #104's `RECEIVER_EXPORTED` history, #101's
hand-written FTS triggers, #125's `recursive_triggers` mechanism, and #103's worry about existing DB
rows. In every case the code was defensible and the explanation was wrong — and the explanation is
what the next person inherits. Two issues (#117, #125) were filed on mechanisms that do not exist.
**Before building on "this used to be different" or "this happens because X", check it:** `git log -p`
disproved one in a single command, and grepping a library's bytecode disproved another.

**A correct fix can create a new user-facing problem.** Twice: #122's in-flight guard left the
re-fetch button enabled but rejecting for the length of a sync pass, and #103's paragraph formatting
silently broke in-note search highlighting. Neither was findable by tests — both needed someone to
trace an interaction between two pieces of correct code. **When a fix changes which paths are hot,
review the newly-hot paths.**

**Test quality is verified by breaking the implementation, not by counting assertions.** Replace the
function body with `return input` and see which tests survive; reinstate the removed guard and
confirm the test that documents its removal goes red. Several tests that looked like pins turned out
to pass against an identity implementation.


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

**A positive result is not a verified result without its negative control.** Confirming adb still
reached the `DUMP`-gated receiver proved only that adb was not blocked; an attribute Android
silently ignored would have looked identical. Gating the receiver on a permission *nobody* holds and
watching delivery stop is what actually proved enforcement. Both cases printed
`Broadcast completed: result=0`. **Ask what a broken version would look like, and check that it
looks different.**

**A test written from the same list as the fix inherits the fix's blind spot.** The `SafeFilename`
guards were scoped to the two handlers #104 named, and the new test enshrined exactly those two —
certifying the gap as covered. `PROBE_DELETE` was worse than either (it brute-forces CMD `0x0D`–`0x17`
at the firmware until a file disappears) and had only an `isNotBlank()` check. Adversarial review
found it; the test could not have.

**Ask what the artifact actually contains before publishing it.** The first "structure-only" fixture
zeroed payload inside validated frames but left gap-span bytes — real audio that merely failed to
parse. It decoded at mean −29.3 dB / max 0.0 dB, **louder than the source recording**, and was one
commit from a public repo. `ffmpeg -af volumedetect` plus a non-zero-byte count is a five-second
check. Run it on anything derived from user data.

**The owner's domain knowledge beats hours of inference.** Two hypotheses this session — "fresh
recordings never sync" and "the device never stopped recording" — were both wrong, and the second
was disproved by `isRecording=false` sitting in logs already collected. The actual answer ("the app
cannot trigger hardware recording; that path is disabled") was unobtainable from the device. **Ask
early when behaviour contradicts the model.** Note also that the discarded silence hypothesis was
still worth testing: ruling it out produced the evidence that found the real cause.

**Check what a trigger does before firing it.** `SYNC` reads as innocuous and executes hardware
deletions. Reading `syncAllBleFiles` first cost one minute; firing it blind with a non-empty
pending-delete queue would have destroyed recordings off the FW920.

**A fix can make a latent bug into the common path.** #119's guard was correct, but it routed
traffic onto `restoreBackup`, which deleted the `.bak` *outside* its `runCatching` — so a copy that
threw partway left the user with a partial file and no backup. Rare before, routine after. **When
adding a guard, review the code the guard newly depends on**, not just the code you wrote.

**Two greens after a red is not a fix.** A test failed on Linux CI and passed on re-run. Two
hypotheses (test ordering, a real media API in the coroutine) were both wrong. What actually found
it was making CI *print assertion messages* — the failure then named itself in one run:
`doAnalyze` independently calls `deletePartsOf`/`save`, so `coVerify(exactly = 1)` was
scheduling-dependent. **Fix your ability to see the failure before theorising about it.** CI now
prints full exception output; that change was worth more than either hypothesis.

**Room owns FTS trigger synchronisation — do not hand-write them.** #101 originally created three
sync triggers. Room's `onPostMigrate` generates its own `room_fts_content_sync_*` triggers, and
`DBUtil.dropFtsSyncTriggers` only ever removes triggers with **that** prefix — so hand-written ones
would have been welded into the owner's database forever, diverging from every clean install. They
were also semantically wrong (`DELETE FROM fts WHERE docid` on a `content=` table re-reads the
content table, so AFTER-triggers un-index the wrong terms; that is why Room uses BEFORE). In
production the symptom was masked by Room's triggers firing first. **Correctness by accident of
ordering is not a property to ship.**

**`MigrationTestHelper` does not run `onPostMigrate`.** A migration test therefore exercises a
trigger topology that exists on **no real device**, and will happily pass on a database that returns
stale search hits. Live-topology behaviour needs a real `Room.inMemoryDatabaseBuilder` test.

**A migration test that only asserts rows survived proves almost nothing.** Assert the *derived*
state too — for #101 that meant querying through FTS after migrating, because an empty index would
leave every existing recording unsearchable while the row-survival assertions stayed green.
