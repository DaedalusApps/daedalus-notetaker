# Handoff Brief — Daedalus Notetaker

Written 2026-08-14, closing session 8. Replaces the previous version. Read this whole file before
starting — it is written to be the only context you need.

---

## Current state

- **`main` = `8db0bf9`** (merge of #158), clean working tree, in sync with `origin/main`, **green
  on CI** (verified — and gate on the *conclusion*, not just non-pending; see process notes).
- **`.\gradlew :app:testDebugUnitTest` → 510 tests / 0 failures / 1 skipped.** The single skip is
  `Mp3FrameScanTest.realFileCrossCheck` and is **by design** — a *pass* there would mean a
  regression.
- **The board holds exactly one open issue: #119.** It is open **by design** (root cause
  unresolved); everything else filed against this project was closed this session or before. Do
  not let a commit message close it (see the closing-keyword trap below).
- **Phone** (Galaxy S26 Ultra) is on the **release** build built from `8db0bf9`, installed with
  `adb install -r`, verified launching and BLE-connected at session close.
- **Phone data: 21 files / the original 20 recordings verified byte-identical** to the
  session-start baseline at close, plus one throwaway (below). Off-device backups at
  `C:\Users\franc\local-persist\daedalus-notetaker-db-snapshots\2026-08-14-session8\`
  (recordings baseline + MD5 manifests, db+wal+shm pulled via a temporary debug install,
  all three throwaway download variants). The session-7 snapshot dir (incl. the irreplaceable
  pre-migration schema-12 db) is separate and untouched.

### Merged this session (session 8)

| PR | Issues closed | What |
|---|---|---|
| #153 | **#150** | `autoTriggered` guard: auto-analyze re-checks full eligibility (`summary` blank AND not `analysisFailed`) under `heavyWork`; review found a second racing call site (`stopLocalRecording`) and the guard now covers both; skip path never flips `_isProcessing`/foreground service |
| #154 | **#144, #141** | All FW920 link operations serialised behind one per-instance `linkMutex`/`withLink` gate (drain-on-entry, `collectFileListLocked` for in-lock callers); deleteFile bails on a timed-out stage ack; downloadFile releases the lock before post-transfer local work; poller skips when the link is busy. Hardware-verified (see below) |
| #156 | **#148, #155** | Superseded stale GATTs torn down in **all four** callbacks (`onConnectionStateChange`, `onServicesDiscovered`, `onMtuChanged`, and #158 added `onDescriptorWrite`); failed-status connect goes **DISCONNECTED** (not ERROR) so auto-reconnect resumes — `onScanFailed` precedent; `@Volatile` on `bluetoothGatt`/`writeChar`; `closeAndClearGatt` helper; #151's `requestMtu` status guard folded in |
| #158 | **#147, #157** | Notify setup failures surfaced: `onDescriptorWrite` branches on `status` and drops superseded-connection callbacks; descriptor completion correlated **per characteristic UUID** (map of deferreds — positional channel pairing removed); missing characteristic logged; probe path reports real results. Plus the #157 test-leak fix (below) |

Also: #119's re-download integrity detection **already existed** on `main` (commit `8aded6a`,
2026-08-13) — see the #119 section; nothing new was needed there.

### Hardware verification done this session (all on the real FW920 + phone)

- Three consecutive enumerations **18/18** (16 real files + 2 throwaways), no duplicates/omissions.
- A 10.3 MB download under deliberate mid-transfer SYNC pressure: **0 frame-scan gaps**, and two
  consecutive downloads **byte-identical (MD5 `84489f4f…`) across different chunk framings**
  (59,116 vs 59,085 chunks) — content-stable transfers.
- Poller observed skipping during a transfer and resuming after.
- Not hardware-exercised this session: the delete path's serialisation (unit+review verified;
  its hardware flow ran in session 7 under #116 and is semantically unchanged) and #147/#148's
  failure branches (they need induced GATT failures; unit-verified only).

---

## #119 — the one open issue: substantial new evidence, root cause still unknown

Full data in the issue comments (2026-08-14). Summary:

- **A completed transfer measurably 380,384 bytes short was observed**: the fresh throwaway's
  first download returned 9,957,404 bytes with a valid EOF ack; two later downloads (post-#144
  build) both returned 10,337,788 bytes, byte-identical — that is the file's true stable size.
  **Caveat:** the FW920 exhibited a stop-overrun on that same file (below), so download 1 may have
  been a prefix-read of a still-growing file rather than a truncated transfer of a finished one.
  Distinguishing those two is the next repro question.
- **`sizeBytes` from enumeration is garbage, measured**: reported/actual ratios 3.2×–494× across
  all 16 files with known local copies; a fresh file reported exactly 2^24. No calibration can
  rescue it (D58). Any integrity check must use a prior local copy, never device metadata.
- **Detection for the re-download case already ships** (commit `8aded6a`, session 7): a
  re-download smaller than the prior copy/`.bak` is rejected, the backup restored, an error
  surfaced. **First-ever downloads remain uncovered** — exactly where this session's short
  download landed. Covering first downloads has no size reference; that gap is inherent, not an
  oversight.
- **FW920 finalisation delay (new)**: a just-recorded file answers CMD 0x0B with the ready ack
  then streams nothing (0 bytes, repeatedly), for at least ~7 minutes and up to ~50 minutes after
  recording. Reconnecting does not help. The app logs "will retry next sync", which is correct
  behaviour, but expect this when making repro assets.
- **FW920 stop-overrun (new, one clean observation)**: `RecordingStopped` was acked at 3 min but
  the file's content ran to ~43 min (~4.0 KB/s encode rate). A second, 20 s recording stopped
  correctly per status polls. Evidence too thin to file; watch for a second occurrence.

**Repro assets:** throwaway `20260814131532` (10,337,788 B, MD5 `84489f4f…`) is on the FW920 *and*
the phone (visible in the app as a ~43 min recording — mostly silence); small throwaway
`20260814150629` is on the FW920 only (its downloads all zero-byted; likely finalised by now).
All download variants preserved in the session-8 local-persist dir. **The owner has not yet
decided whether to delete the throwaways** — ask before removing them; the phone one is
user-visible clutter, but they are the only on-device repro assets.

---

## Hard constraints — unchanged, still not negotiable

- **The phone holds real user recordings, frequently the only copy.** Never `pm clear`, never
  `adb uninstall`, never backup-import over live data. **`adb install -r` only.**
- **Debug↔release swaps install in place** (release signed with the debug keystore,
  `android/app/build.gradle.kts`). The release build is **not debuggable** — `run-as` refused; to
  read the db, install a debug build first, then swap back.
- **Installing an APK kills any running analysis. Run the idle check as its own command and READ
  it before installing** — session 8 chained check+install in one command and killed a live
  auto-analysis (no data lost; the analysis re-ran). `adb shell "top -b -n 1 -q -o PID,%CPU |
  grep -w <pid>"`.
- **Finish every session with `assembleRelease` installed on the phone.** (Done.)
- **Room uses WAL** — pull `-wal` alongside the db. **Use `adb exec-out` for binaries** (CRLF
  corruption otherwise). **Capture logcat live**, before triggering.
- **⚠ `SYNC` processes queued deletions before syncing** and the app auto-syncs on BLE connect —
  check `SELECT COUNT(*) FROM recordings WHERE pendingDelete=1` on a pulled db copy before
  letting the app connect. (Session 8 verified 0 before any device work.)
- Device-test data-integrity protocol: baseline-pull recordings+db with MD5s before device work,
  destructive tests only against throwaways you pushed, re-pull and diff MD5s afterwards, report.

### Device gotchas carried forward (all still true)

- `MSYS_NO_PATHCONV=1` for device-side paths in Git Bash; Windows-form paths for local APK args.
- Android vs Windows `md5sum` formats differ — normalise with `sed -E 's/[ ]+\*?/  /'`.
- BLE log tags: `BleManager`, `DaedalusSync`, `DaedalusADB`, `DaedalusAI`, `BleAudit`,
  `AudioRecorder`. `"Downloading…"` is a StateFlow, not a log line.
- `am broadcast` prints `result=0` regardless of delivery — only live logcat tells the truth.
- ADB triggers (debug build, app foregrounded; source of truth `AdbActions.kt`) — the session-7
  list still stands, plus `START_RECORDING`/`STOP_RECORDING` (used to make FW920 throwaways —
  the protocol is download-only, so throwaways are made by recording on the device itself).
- DB keys: BLE-synced recordings have no extension; imports keep theirs.
- **adb daemon restarts kill background logcat captures silently** — check the capture is alive
  (file growing) after any long pause.

---

## Working process — what session 8 confirmed and added

Load **`apply-working-process`** first, syncing it from
`~/projects/fable-quality-library/skills/apply-working-process/` before loading. Orchestrator
delegates and never edits code; cheap-model implementers; fresh reviewer per diff; three gates
(`/simplify` → `/security-review` → `/code-review`) with every finding fixed; issues before
branches; decisions logged to `prd/DECISIONS.md` same-session (D58–D61 cover session 8; D58 has a
correction entry — read it).

**New process lessons, paid for this session:**

- **Parallel implementers MUST get isolated worktrees** (the Agent tool's worktree isolation).
  Two agents sharing the main checkout cross-contaminated branches — one agent's commit landed on
  the other's branch and had to be rebased out. Never again.
- **The push gate reads the SESSION checkout's HEAD** — before pushing a branch developed in a
  worktree, check it out in the main checkout and write `.git/security-review-ok` there, as its
  own command.
- **Worktree removal on Windows can fail with "Filename too long"** (gradle artifacts). The
  registration is usually removed anyway; delete the leftover directory with the robocopy
  `/MIR`-from-empty trick (robocopy exit code 2 is benign).
- **Two-round review cap:** #148 needed a third round (a verifier-isolated one-line guard) and
  the deviation was flagged to the owner rather than hidden. The cap held everywhere else; both
  big branches converged in two.
- **A same-commit CI failure twice is not a flake** — stop rerunning and diagnose. Session 8's
  instance root-caused to leaked test coroutines (next item).
- **Test-leak class (#157, closed with evidence):** tests that drive `BleManager`'s GATT
  callbacks launch `initJob` on the manager's real `Dispatchers.IO` scope; leaked coroutines
  outlive the test and hit `Log` after `unmockkStatic` — kotlinx-coroutines-test's global handler
  then reports `UncaughtExceptionsBeforeTest` on the *next* `runTest` class (`TodoViewModelTest`,
  deterministically). Fix: `manager.destroy()` + bounded `scope.job.join()` in teardown *before*
  unmocking Log — and destroy every extra instance a test creates. **If that signature ever
  reappears on ≥`8db0bf9`, reopen #157** — it would mean a second escape path.
- **Check a claim against the code before designing from it.** D58's detection design was derived
  from the previous handoff's "there is still no end-to-end integrity check" — which was stale;
  the guard already existed (`8aded6a`). The handoff's own "check the history" lesson, self-inflicted.

**Standing traps still in force:** gate CI on explicit `pass`; write the security marker as its
own command; never put `fix/closes/resolves` before an issue number in a commit subject unless
that merge should close it (`Refs #N` otherwise) — and run `gh issue list --state open` after
every merge, comparing against expectation. Session 8 did this after all four merges; the board
matched every time.

---

## Judgment lessons worth inheriting (new this session; session-7 list still applies)

- **Reviews kept finding real bugs in the fixes** — every fix round this session surfaced at
  least one genuine defect (the ERROR dead-end, the descriptor-channel desync, the same-UUID
  cross-connection race, the notificationSet/writeInitiated conflation). Fresh-reviewer gates are
  not overhead in this codebase; budget for two rounds.
- **Identical UUIDs across devices break UUID-keyed correlation.** Both FW920 units expose the
  same characteristic UUIDs, so correlating by UUID alone cannot distinguish connections — a
  gatt-identity guard is required alongside it. Generalisation: a correlation key must be unique
  across *all* concurrent sources, not just within one.
- **A dropped errorMessage can be the right call.** Notify-failure surfacing via
  `BleState.errorMessage` was reversed (D60): this UI renders errorMessage only when
  disconnected, so the write was invisible when relevant and stale afterwards. Check where a
  surface is actually rendered before writing to it.
- **Serialisation trades corruption for latency — document the trade.** Post-#144, user ops
  queue behind long transfers instead of corrupting them; repeated failed connects now retry via
  scan with no backoff (accepted, D59/PR #156). If users report "stuck" UI during downloads or
  battery churn on marginal RF, these are the knobs.
- **The FW920's firmware is stranger than the protocol docs:** finalisation delay, stop-overrun,
  garbage sizeBytes — measure against the real device before trusting any field or ack.
