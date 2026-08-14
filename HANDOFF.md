# Handoff Brief — Daedalus Notetaker

Written 2026-08-14, closing session 7 (a live-device phase). Replaces the previous version. Read
this whole file before starting — it is written to be the only context you need.

---

## Current state

- **`main`** = `5ca5047` (merge of #145), clean working tree, in sync with `origin/main`, and
  **green on CI** (verified).
- **`.\gradlew :app:testDebugUnitTest` → 490 tests / 0 failures / 1 skipped.** The single skip is
  `Mp3FrameScanTest.realFileCrossCheck` and it is **by design** — a skip there is correct; a *pass*
  would mean something regressed.
- **`:app:assembleRelease` builds clean** at versionCode **368**, which is what is installed on the
  phone.
- **Phone** (Galaxy S26 Ultra) is on the **release** build, **versionCode 368**, `versionCode =
  gitCommitCount` (`app/build.gradle.kts:41`), installed with `adb install -r`. Verified
  non-debuggable, launches clean, BLE connected.
- **The schema 12→13 migration has run, on the real database, and is DONE.** `user_version` is now
  13, `integrity_check ok`, all 40 rows preserved and byte-identical before/after (270,329 chars
  both sides, no MD5 changed). FTS was back-filled **40/40 rows** — `MATCH 'actually'` returns
  exactly the same 24 filenames as a pre-migration scan of the raw transcripts, an acceptance
  criterion chosen before migrating so it was falsifiable. This is no longer a hazard to watch for.
- **The app's own search returns 12 for that same query, not 24 — this is correct, not a bug.**
  It is the parent/part split: 12 parent rows + 12 `_pN` part rows both match, and the UI shows
  parents. Do not re-investigate this.
- **#119, #141 and #144 are the open issues.** #141 is partially fixed by PR #145 but stays open
  — see below. #136, #116, #125 are all closed this session (see below).

### Device access: release is not debuggable — read this before trying to pull the db

The installed release build is **not `debuggable`**, so `run-as` is refused and
`daedalus_notes.db` is unreachable by the normal pull method. This cost real time this session and
is not obvious from the manifest alone.

- The db lives in **internal storage** (`/data/data/com.daedalus.notes/databases/`), **not** the
  external files dir — only a stale June `-shm` sits there. Session 6 could pull the db only
  because a debug build happened to be installed at the time; that was incidental, not a documented
  path.
- **To read the db at all, a debug build must be installed first** (`run-as` requires
  `debuggable="true"`), then swap back to release afterward.
- **To get a *pre-migration* snapshot specifically**, build debug at the last schema-12 commit
  (`d47ba0a`, git commit count 313) and install it **without launching it** — launching runs the
  migration.
- `git worktree add` needs `local.properties` copied in manually (it's gitignored) or the build
  fails with "SDK location not found".
- `adb install` needs a **Windows-form path** for the local APK even when `MSYS_NO_PATHCONV=1` is
  set for device-side paths — that env var only affects device paths, not the local file argument.

The pre-migration schema-12 db from this session is preserved outside the repo at
`C:\Users\franc\local-persist\daedalus-notetaker-db-snapshots\2026-08-14-session7\`. **It cannot be
recreated** — the source data (the owner's own real, unmigrated database) no longer exists on the
phone.

**Merged this session (Session 7):**

| PR | Issue | What |
|---|---|---|
| #138 | **#136 closed** | Six raw `Dispatchers.IO` sites converted to injectable dispatcher; vacuous tests fixed; `deleteOnExit()` leak and dispatcher `Delay` gap also fixed |
| #139 | **#116 closed** | `buildPacket` now throws on oversize payload instead of silently wrapping the length byte; filename builders clamp to a derived, wire-format-correct limit |
| #140 | refs #125 | Permanent debug-only `DB_PRAGMA` ADB probe added; used to verify #125 on hardware, then closed manually (no code fix needed) |
| #145 | refs #141 (stays open) | Per-instance `Mutex` serialising `collectFileList()`; `refreshStatus()` acquires the **same** mutex; `responseChannel` drained of stale residue before each `PKT_LIST_FILES` send. Hardware-verified 16/16, no duplicates, no omissions, three consecutive runs. Does not close #141 — see below |

**Closed in session 7: #136, #116, #125.** **#119 stays open** — extensively re-tested, not
reproduced; see below. **#141 stays open** — partially fixed; two unguarded routes into the same
bug class remain, tracked as new issue **#144**.

---

## Open issues & next session starting point

| # | Title | Note |
|---|---|---|
| **#119** | Root cause: why a transfer after an interrupted one comes back short | Unchanged since last write-up; still not reproduced. Needs a fresh hardware repro asset (the old one is deleted off the FW920 — see below) |
| **#141** | Concurrent `collectFileList()` enumerations interleave, producing a wrong device file list | **Partially fixed by #145** — `collectFileList()` vs itself and vs `refreshStatus()` is now serialised and hardware-verified. **Stays open**: `downloadFile` and `deleteFile` still race the same unserialised `responseChannel`. See below and #144 |
| **#144** | `responseChannel` has no request-correlation id; every guard added reveals another unguarded consumer | **New.** `collectFileList()` racing `downloadFile()` silently corrupts downloaded audio; the status poller can also steal `deleteFile`'s commit ack, reporting a successful delete as failed. See below |

### #119 — still open, not reproduced, and the leading hypothesis took a hit

Eight transfers of the throwaway file this session, in order: a clean-start re-download (completed);
a second clean-start re-download (completed); a re-download deliberately interrupted mid-transfer
by `am force-stop` (119,272-byte partial, full 337,148 preserved in `.bak`); a re-download from
that dirty state (completed); three further re-downloads each deliberately interrupted mid-transfer
(partials at 12,776 / 65,024 / 73,704 bytes); and a final re-download after those three consecutive
interruptions (completed). **All four completed re-downloads returned the full 337,148 bytes,
MD5-identical (`a0e25951817b9161008bd0a8b9aae194`).**

This matters because the previous handoff's leading hypothesis — a stale FW920 stream position
after repeated interruptions — is exactly the scenario these eight transfers tested, and it held
up fine every time. That explanation is now **weaker**, not stronger. **Do not close #119** —
failure to reproduce is not proof of absence, and one anomalous 217,412-byte transfer was seen and
confirmed on hardware in session 6.

Useful side-confirmations from the same eight runs, not causes:
- The interruptions independently confirmed #123's backup/restore works correctly on hardware:
  partial file + full `.bak` restores to byte-identical.
- A `Status(cmd=15,…)` packet was seen arriving mid-transfer and logged as `unexpected=` and
  discarded. This is normal, handled behaviour (interleaved control packets in the data stream),
  **not** offered as a cause of #119 — recorded only so it isn't mistaken for one later.

**The throwaway `20260813084543` has been DELETED off the FW920** (it was the delete target used
to verify #116's fix on hardware). Its local phone copy still exists, MD5
`a0e25951817b9161008bd0a8b9aae194`, but #119 no longer has a ready-made FW920-side repro asset — a
new throwaway recording must be made and pushed before the next attempt.

There is still no end-to-end integrity check on a transfer, and the device's `sizeBytes` is still
unusable for one (wrong unit). The EOF ack remains the only completion signal, and this issue is
exactly the case where it lied.

### #106's residual, and why more captures will not close it (closed, but keep this)

`recordGap`-on-unresyncable-EOF, `isBenignTrailer` and `isBoundedApeV2Footer` are exercised only by
synthetic fixtures; #120's real captures only ever hit `recordGap` with a **non-null** resync
position (mid-stream loss), 28 and 70 times. **An interrupted BLE transfer truncates cleanly at a
frame boundary — it does not garble to EOF** — so capturing more interrupted transfers can never
reach the trailing-span paths, however many you take. That needs a different failure mode. Do not
"fix" it by adding synthetic fixtures — considered and rejected by the owner (D35). Corroborated by
session 7: four deliberate mid-transfer interruptions produced clean truncations at 119,272 /
12,776 / 65,024 / 73,704 bytes, consistent with clean frame-boundary truncation.

### #141 — partially fixed by PR #145, stays open

Filed 2026-08-14, release build 356. `BleManager.collectFileList()` sends `PKT_LIST_FILES` then
loops `awaitResponse(expectedCmd = 0x0A)` into a **local** `collected` list. It was called from five
sites (`BleManager.kt:465, 703, 834, 858, 899`) with nothing serialising them. When two enumerations
overlap, both loops consume the same notification stream, so entries get split between them and can
be delivered to both.

Measured on hardware before the fix: the FW920 holds **16** files. A non-overlapping pass reported
16/16 correctly. An overlapping pair reported **9** and **23** — the 23 collector logged 32 entry
lines for 16 unique filenames (duplicates), the 9 collector was missing files.

**What #145 shipped:** a per-instance `Mutex` serialising `collectFileList()` against itself;
`refreshStatus()` acquiring the **same** mutex (the 15 s status poller was eating `FileList`
entries mid-enumeration — a second, independent way to reproduce the same symptom); and a drain of
`responseChannel` before each `PKT_LIST_FILES` send (an enumeration that exits on its 3 s idle
timeout leaves its remaining entries *plus the end-of-list sentinel* queued for the next caller,
which corrupts the *next* enumeration even when nothing overlaps it in time). Hardware-verified:
three consecutive enumerations at **16/16, no duplicates, no omissions**, poller running
throughout — where the same conditions previously produced 9 and 23.

**Why it stays open:** the obvious fix looked like the whole fix, but two more unserialised routes
into the same bug class remained — `downloadFile` and `deleteFile`. They are real, hardware-relevant,
and now filed separately as **#144** because they're a different code path with different
consequences (data corruption and a false failure report, not just a wrong list). See #144 and the
pattern note below.

### #144 — NEW, unserialised `responseChannel` consumers outside `collectFileList`

`collectFileList()` racing `downloadFile()` consumes audio chunks and silently corrupts the
downloaded file: `downloadFile` reads `responseChannel.receive()` directly (`BleManager.kt:752`),
`awaitResponse` maps `AudioChunk -> false`, and dropped chunks are written contiguously with no gap
detection — the download still reports success. Reachable from `DeviceViewModel.kt:42-48`, where
`refreshFiles()` and `downloadFile()` launch unserialised.

#144 also records a sibling case: the status poller can steal `deleteFile`'s `0x0D` commit ack, so
a **successful** delete reports "Failed to delete".

### The pattern behind #141 and #144, worth naming so it isn't rediscovered piecemeal

`responseChannel` is shared by every consumer with **no request-correlation id** — a response is
matched by expected packet type, not by which call sent the request. Every guard added so far has
revealed another unguarded consumer: #141 guarded `collectFileList` against itself and against
`refreshStatus`; #144 shows `downloadFile`, `deleteFile`'s two `0x0D` `sendAndAwait` calls, and
`runInitSequence`'s six `sendAndAwait` calls are **still unguarded**. The FW920 link is
single-command-at-a-time; **the durable fix is serialising all link operations, not guarding them
one at a time.** That work belongs to #144, not to another one-off mutex.

### #125 — CLOSED, verified on hardware, no code change needed

Verified directly from the app's own Room WRITE connection on the real phone, via the new
`DB_PRAGMA` probe: `recursive_triggers=1`, `temp_store=2`, `user_version=13`, and exactly the four
`room_fts_content_sync_*` triggers with **no** hand-written ones present. On the real db, after
real upserts: FTS `integrity-check` **passes** (the exact check the issue claimed would report
"malformed"), 0 orphaned docids, 0 rows missing from the index, 0 stale entries,
recordings=40 / fts=40.

A permanent debug-only ADB action now exists to re-take this reading in one command — add it to
the trigger list below:

```
adb shell am broadcast -a com.daedalus.notes.DB_PRAGMA -n com.daedalus.notes/.AdbReceiver
```

### #116 — CLOSED, and its prescribed fix was refused because it would have destroyed data

The issue asked for `.take(14)` on filenames reaching the delete/download packet builders. Real
filenames reaching `deleteFile`/`downloadFile` run to 22 characters:
`20260806103204_p1` (17), `20260714131800.m4a` (18), `conv_20260804080519.md` (22). `.take(14)`
turns `20260806103204_p1` into `20260806103204` — **a different, real file** — so applying the
issue's literal fix would have hardware-deleted the wrong recording. Commit `a143d0b` had already
removed that clamp deliberately, and this session confirmed why it stayed removed.

What shipped instead: `buildPacket` now does `require(payload.size <= 255)` (throws instead of
silently wrapping the single-byte length field), and all three filename builders clamp via a
shared `MAX_PROTOCOL_FILENAME_CHARS = 249` — derived, not guessed, because `parseResponse`'s
CMD `0x0A` computes `nameLen = len - 6` with `len` unsigned, so 249 is the longest name the wire
format can express; no device-supplied name is ever truncated. `249 + 4 = 253 <= 255` is enforced
by an init-time check.

Hardware delete verified against the (now-deleted) throwaway: stage → commit →
`stillPresent=false` → `true`; FW920 file count went 17 → 16.

**Also record: the FW920 unit reports 14-character filenames, not 19-character ones.** An
implementer asserted the FW920 reports 19-char `Note-<timestamp>` names and wrote that into a code
comment as fact; the hardware contradicted it and the comment was corrected. The conclusion
(clamp is needed / 249 is the right number) was right regardless — but the stated reason for the
original 14-char assumption was invented, not observed. Third instance this session-pair of a
correct conclusion resting on an invented premise (see judgment lessons below).

### #136 — CLOSED, report honestly: cause removed, rate unmeasured

Six raw `Dispatchers.IO` sites in `RecordingViewModel` (roughly `init`, `fullAutoSync`,
`syncFiles`, `doAnalyzeExclusive`, `exportNote`, `exportLibraryAnswer`) converted to the injectable
`ioDispatcher`, closing the mechanism that let a coroutine escape onto a real thread past a test's
`advanceUntilIdle()` and throw into whichever test ran next.

Adversarial review found **three of the four new "routing" tests were vacuous** — they passed even
with the fix reverted, because `init` dispatches unconditionally so the dispatch count was already
> 0 before the method under test ran. They were rewritten to assert a **delta**, and each was
individually proven red-then-green.

Two further real findings from the same pass:
- `deleteOnExit()` does not remove non-empty temp dirs — 14 had silently accumulated.
- The test counting dispatcher did not delegate `Delay`, which would have silently reintroduced
  the exact same leak class the first time a `delay()` call landed inside an IO block.

**Report this as "cause removed, rate unmeasured", not "proven fixed."** 3 CI re-runs on the fixed
commit came back green, which is weak evidence given the original failure was one observed
occurrence — not a measured rate before or after.

---

## The 6-pillar suite: status

Unchanged from session 6 — no pillar work happened this session.

| Pillar | Status | Detail |
|---|---|---|
| 1. Background Service | **Delivered & Wired (#102)** | `AnalysisForegroundService` started/updated/stopped during BLE sync, re-download, and AI analysis. Declared `FOREGROUND_SERVICE_DATA_SYNC` in manifest for API 34+ compliance. |
| 2. Calendar | **Delivered** | `TodoScreen.kt:302`. Device-verified: chooser launched, +191 ms |
| 3. Speed / Skip | **Delivered** | Speed state lives in `RecordingViewModel`. Device-verified |
| 4. Speaker Formatting | **Re-scoped & delivered (#103)** | Shipped as paragraph formatting, not speaker attribution. `SpeakerDiarizer` deleted; `TranscriptFormatter` renders paragraphs at display time |
| 5. FTS4 Search | **Delivered (#101), migration now run on the real DB** | `RecordingFts` (`@Fts4(contentEntity)`), schema 12→13. Back-fill verified 40/40 on the real database this session — see Current state above |
| 6. Storage Repair | **Deleted (#100)** | Re-download via BLE (#108 fixed) covers recovery non-destructively |

---

## Hardening candidates found on a now-deleted stale branch — not filed as issues yet

Found in August, lost when the branch went stale, and rediscovered (row 1, independently, during
the #141 work) — the finding has now been paid for twice, which is why it's recorded here instead
of left on a branch again. Honest confidence levels, not a to-do list:

| Item | Location in current main | Confidence |
|---|---|---|
| `responseChannel` never drained before a new request | `BleManager.kt:690, 741, 913, 985` | confirmed by reading code |
| `onDescriptorWrite` ignores `status`; `enableNotification` is fire-and-forget | `BleManager.kt:305-312, 336-347` | confirmed |
| GATT leak on superseded stale connect (stale `STATE_CONNECTED` neither disconnects nor closes) | `BleManager.kt:229-235` | confirmed |
| MediaCodec `INFO_OUTPUT_FORMAT_CHANGED` never handled → decoder's real sample rate never picked up → wrong resample rate into Whisper → silently degraded transcripts | `TranscriptionService.kt:119-120, 167` | **plausible, NOT confirmed against real audio** |
| Missing `autoTriggered` guard → manual analyze racing auto-analyze re-runs a full Whisper+Gemma pass | `RecordingViewModel.kt` ~`:963` | confirmed, narrow window, wasted compute only |

A sixth candidate — `requestMtu` called without checking connect status — is **low confidence**,
based on known Android OEM quirks rather than anything observed on this hardware.

---

## Hard constraints — these are not negotiable

- **The phone holds real user recordings, frequently the only copy** (the FW920 source is often
  already deleted). Never `pm clear`, never `adb uninstall`, never backup-import over live data.
  **`adb install -r` only.**
- **Debug↔release swaps install in place** because release is signed with the **debug keystore**
  (`app/build.gradle.kts:49`). This is why no wipe is needed to get ADB hooks on the phone.
- **The release build is not `debuggable`.** `run-as` is refused against it, so the db is
  unreachable without first installing a debug build (see the device-access section above).
- **Installing an APK kills any running analysis.** Check the app is idle first
  (`adb shell "top -b -n 1 -q -o PID,%CPU | grep <pid>"`) — never install mid-run.
- **Finish every session with `assembleRelease` installed on the phone.**
- **Room uses WAL.** Pull `daedalus_notes.db-wal` alongside the db or you get a stale copy.
- **Use `adb exec-out`, not `adb shell`, to pull binaries on Windows.** `adb shell` applies CRLF
  translation and silently corrupts them.
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

Session 7's data, updated: the phone now holds **20 recordings / 53,541,678 bytes**. Three files
were deliberately removed with owner approval after the earlier 23-file baseline: two stale `.bak`
files from session 6 and the throwaway's local copy. All survivors verified byte-identical.
Off-device backups (pre-migration schema-12 db, post-migration db, and all 23 pre-cleanup
recordings) live at `C:\Users\franc\local-persist\daedalus-notetaker-db-snapshots\2026-08-14-session7\`
— **outside the repo**, and the pre-migration db **cannot be recreated**. The scratchpad holding
session 6's baseline was session-scoped and is gone; re-pull your own baseline for session 8.

### Device-work gotchas that cost real time

- **Git Bash mangles device paths.** `adb pull /sdcard/...` becomes
  `C:/Program Files/Git/sdcard/...` and fails. Export `MSYS_NO_PATHCONV=1` for every adb command
  that names a **device-side** path — it does not help with local (Windows-side) paths; those need
  Windows-form paths passed as-is (see the `adb install` note above).
- **`adb pull` on a directory is binary-safe** and is the right tool for the recordings; the
  `adb shell` CRLF warning applies to `adb shell cat`. Use `adb exec-out` for the db/wal/shm.
- **Android `md5sum` and Windows `md5sum` format differently** — two spaces vs ` *` (binary marker).
  A naive `diff` of the two reports every file as changed when nothing has. Normalise with
  `sed -E 's/[ ]+\*?/  /'` before comparing.
- **Do not sum sizes from `ls -la`** — it includes the `total` line and `.`/`..`. The per-file MD5
  comparison is the authoritative check.
- **The BLE log tag is `BleManager`, not `DaedalusBLE`.** Filtering on the wrong one makes a
  perfectly healthy transfer look completely silent. Real tags: `BleManager`, `DaedalusSync`,
  `DaedalusADB`, `DaedalusAI`, `AudioRecorder`.
- **`"Downloading …"` is a StateFlow update, not a log line.** Its absence from logcat means
  nothing.
- **The phone-mic path records AAC/`.m4a`** (`AudioRecorder.kt:36-37`), not MP3. It cannot produce
  fixtures for `Mp3FrameScan`, which only sees MP3s arriving over BLE.

> **⚠ `SYNC` IS NOT A READ-ONLY TRIGGER.** `syncAllBleFiles` (`RecordingViewModel.kt:379-392`)
> processes queued deletions *before* it syncs: for every row with `pendingDelete=1` it calls
> `bleManager.deleteFile(...)`, wiping the file off FW920 hardware, then deletes the DB row. **The
> app also auto-syncs on BLE connect**, so merely launching it with the device in range can fire
> those deletions. **Check `SELECT COUNT(*) FROM recordings WHERE pendingDelete=1` on a pulled copy
> of the db before letting the app connect.**

**ADB triggers** (debug build only; app must be foregrounded). Single source of truth is
`AdbActions.kt`; a test enforces that handlers and the dynamic `IntentFilter` agree.

```
adb shell am broadcast -a com.daedalus.notes.SYNC -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.ANALYZE --es filename "20260812113220" -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.SET_SPEED --ef speed 1.5 -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.FORMAT_PARAGRAPHS --es filename "20260812102746" -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.SEARCH_FTS --es query "initiative" -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a "com.daedalus.notes.ADD_CALENDAR" --es title 'Multi word title' -n com.daedalus.notes/.AdbReceiver
adb shell am broadcast -a com.daedalus.notes.DB_PRAGMA -n com.daedalus.notes/.AdbReceiver
```
**Quote any extra containing spaces for the device-side shell** — an unquoted title was word-split
in session 6 and `am` parsed a fragment as the package (`pkg=action`), so the broadcast never
matched.

**`am` prints `Broadcast completed: result=0` whether the broadcast was delivered OR refused.** The
exit code is worthless here. Only live logcat tells you which happened.

**DB keys have no extension for BLE-synced recordings** (`20260812102746`), but imports keep theirs
(`.mp3`, `.m4a`). Using the wrong form silently returns "no transcript".

---

## Working process

Load the **`apply-working-process`** skill first — and **sync it from
`~/projects/fable-quality-library/skills/apply-working-process/` before loading**, since the owner
maintains it there and the installed copy can lag. Orchestrator delegates and never edits directly;
fresh equal-or-better reviewer on every diff; three gates (`/simplify` → `/security-review` →
`/code-review`) with every finding fixed; issues before branches; decisions logged to
`prd/DECISIONS.md` **the same session**. `prd/` is gitignored — read `DECISIONS.md` D41–D47 for the
session-7 reasoning behind everything above, and D22–D40 for session-6 background.

### A process trap that cost time in session 7: worktrees and the push gate

**The push gate (`~/.claude/hooks/pre_push_security_gate.py`) resolves HEAD from the SESSION's
checkout, not from the worktree a push is issued in.** A branch developed inside a `git worktree`
can therefore never satisfy the marker check, no matter how many gates actually ran against it —
the gate is comparing the wrong HEAD.

**Fix: check the branch out in the main checkout and record the marker there.** Do **NOT** write
the main checkout's HEAD into the marker just to get past the gate — that records a review as
having happened for a commit nobody actually reviewed, which defeats the entire point of the gate.

### Operational traps carried forward from session 6

**Check the CI *conclusion*, not just that it stopped being pending.** A merge-wait loop of the form
"poll until the output no longer says `pending`, then merge" will happily merge a **failed** build.
Gate on `pass`/`success` explicitly.

**Write `.git/security-review-ok` as its own command.** The push gate reads the marker file, and
chaining `git rev-parse HEAD > .git/security-review-ok && git push` fails — the gate sees the old
value. Run them as separate invocations and verify with `git log --oneline -1`.

**CI is the only Linux available.** Development is Windows; `.github/workflows/ci.yml` on
`ubuntu-latest` is the sole way to see Linux behaviour. `gh run rerun <id>` re-runs the *same
commit*, which is the right tool for characterising a flake.

**A commit message closed a safety-critical issue.** Merging the session-7 handoff PR (#142)
silently closed **#119**. The commit subject was `docs: add #141, restore #106 residual note, fix #119
transfer count` — "fix #119" meant *fix the transfer count for #119 in this document*, but GitHub
read it as a closing keyword and closed the issue on merge. #119 is the open data-loss issue that the
handoff, the issue itself, and the decision log all explicitly say must stay open; a commit-subject
phrase silently overrode all three, with no warning and no failure. It was caught only because the
open-issue list was compared against expectation after merging. **Never put `fix`/`fixes`/`fixed`/
`close`/`closes`/`resolves` immediately before an issue number unless that merge should close it —
use `Refs #N` when merely mentioning one. And run `gh issue list --state open` after every merge and
compare it to the set you expect.** (#119 has been reopened.)

---

## Judgment lessons worth inheriting

**The obvious fix can be half a fix that looks whole.** #141's mutex serialised `collectFileList`
against itself — exactly what the issue described — and its KDoc then implied the list was
trustworthy. Two HIGH paths remained that reproduced the original symptoms. Enumerate the other
routes into a bug class before calling it closed.

**A test can be vacuous because of a timeout, not a weak assertion.** #141's per-instance-mutex
test survived hoisting the mutex into a `companion object`, because the code under test escapes via
a 3 s idle timeout that fitted inside the test's 5 s budget. Mutation testing found it; reading it
would not have. Fourth vacuous test in two sessions.

**An unqualified worry decays exactly like an unqualified assurance.** A concern was published on
#141 as "not a data-loss path" while only the delete path had actually been assessed; the sync path
had not been. Both over-claims become tomorrow's inherited fact.

**A test is not evidence until you have watched it fail for the reason you care about.** Red-first
is necessary but insufficient: a test can be red for the wrong reason and go vacuous the moment an
unrelated line is fixed. #136's "routing" tests passed with the fix reverted because `init`
dispatches unconditionally — a delta assertion was needed, not a raw count. Third occurrence in two
sessions; see the `SpeakerDiarizer` and cadence-detector instances below for the earlier two.

**An issue's prescribed fix can itself be the bug.** #116 proposed `.take(14)` as the remedy for a
protocol-length desync. Applied literally it would have turned a real 17-character filename into a
different real file's 14-character prefix and hardware-deleted the wrong recording. **Check the
remedy against real data before applying it, not just the diagnosis.**

**A correct conclusion can rest on an invented premise and still pass review.** #116's clamp value
was right; the claim that the FW920 reports 19-character names was not — it reports 14. Check the
premise separately from the decision; a right answer for a fabricated reason is a landmine for
whoever inherits the reasoning next.

**Review the justification, not just the diff.** Recurring pattern across sessions 6 and 7:
adversarial review overturning a *premise* rather than finding a bug (#104's `RECEIVER_EXPORTED`
history, #101's hand-written FTS triggers, #125's `recursive_triggers` mechanism, #103's DB-rows
worry, #116's 19-char claim). In every case the underlying code was defensible and the stated
reason was wrong — and the reason is what the next person inherits.

**A correct fix can create a new user-facing problem.** #122's in-flight guard left the re-fetch
button enabled but rejecting for the length of a sync pass; #103's paragraph formatting silently
broke in-note search highlighting. Neither was findable by tests — both needed someone to trace an
interaction between two pieces of correct code.

**Test quality is verified by breaking the implementation, not by counting assertions.** Replace
the function body with `return input` and see which tests survive; reinstate a removed guard and
confirm the test that documents its removal goes red.

**A measurement against real data beats a reviewer's model.** A reviewer flagged a trailing-tag
false positive; the implementer measured it against all real recordings and found zero effect; the
fix was ordered anyway and introduced an unbounded classifier under which a recording that lost all
but 72 ms would display as *undamaged*. Logged as D30.

**Green tests say nothing about integration.** All six pillars had passing tests. Three were not
connected to anything, at the time D33 was written.

**An issue's stated scope is a hypothesis about the bug, not a boundary on it.** #104 described one
hole; there were two, and the undocumented one was worse. Read the surrounding code before
accepting the framing.

**Check the history before building on a claim about it.** `git log -p` has now disproved a
confident causal story about this codebase in more than one session. Cheap check, high yield.

**Ship with the issue open when the issue isn't done.** PRs that name unmet acceptance criteria
and use `Refs`, not `Closes`, are the cheapest defence against overclaiming — see D33.

**A positive result is not a verified result without its negative control.** Ask what a broken
version would look like, and check that it actually looks different — `am broadcast` prints the
same `result=0` whether delivery succeeded or was refused; only logcat tells them apart.

**Ask what the artifact actually contains before publishing it.** Anything derived from user data
needs a direct check of its content, not just its shape (`ffmpeg -af volumedetect`, non-zero byte
counts) — a "structure-only" fixture once decoded louder than the source recording.

**The owner's domain knowledge beats hours of inference.** Ask early when behaviour contradicts the
model; a hypothesis that gets ruled out is still worth having tested, because ruling it out can
produce the evidence that finds the real cause.

**Check what a trigger does before firing it.** `SYNC` reads as innocuous and executes hardware
deletions. Reading `syncAllBleFiles` first cost one minute; firing it blind with a non-empty
pending-delete queue would have destroyed recordings off the FW920.

**Two greens after a red is not a fix.** Fix your ability to *see* the failure (full exception
output in CI logging) before theorising about its cause.

**Room owns FTS trigger synchronisation — do not hand-write them.** Confirmed again this session by
the `DB_PRAGMA` probe: the four `room_fts_content_sync_*` triggers are the only ones present on the
real device, and integrity-check passes because of it.

**`MigrationTestHelper` does not run `onPostMigrate`.** A migration test therefore exercises a
trigger topology that exists on no real device. Live-topology behaviour needs a real
`Room.inMemoryDatabaseBuilder` test — or, as this session showed, a real device.

**A migration test that only asserts rows survived proves almost nothing.** Assert the *derived*
state too. Session 7's real-hardware verification of #101's migration used exactly this: querying
through FTS after migrating and cross-checking the hit count against an independent pre-migration
scan chosen as the acceptance criterion *before* migrating, so it was falsifiable.
