---
description: Fix a red build for the right reason — first failure, classify, reproduce locally, fix or quarantine
---

# /ci-triage

Argument: the failing run/pipeline (defaults to the latest red run on this branch).

## Steps

1. **Read**: the FIRST failure in the run (later ones are cascade); the actual failing output, decisive line quoted. List what changed: the diff, plus what CI re-resolves (dependencies, images, runners) — "no code changed" ≠ "nothing changed".
2. **Classify** (one diagnostic rerun allowed, same commit, only if unsure — same failure means deterministic): CODE BUG (deterministic, from the diff → /debug) · BROKEN TEST (code right, expectation stale → fix the test, but prove the code right first) · FLAKE (same commit passes and fails → step 4) · INFRA (runner died, disk, registry — rerun legitimate for this class only; recurring → escalate) · DRIFT (dependency/image resolved new → pin now, upgrade deliberately via /migrate).
3. **Reproduce locally** with the command CI ran (from the workflow file). Local-passes-CI-fails → diff the environments (versions, env vars, parallelism, timezone, test order) — that diff is the habitat. Prove the fix: the failing run passes, quoted.
4. **Flakes**: stress-run the single test if cheap; root-cause fixable now → fix (shared state, ordering, time, unawaited async, ports); else quarantine with a ticket linked in the skip marker — a skip without a ticket is a deletion. Never sleeps or wider timeouts as the fix.
5. **Main red**: fix forward only if minutes away, else revert first. Entangled commits → `git bisect` with the CI command. Never debug by pushing guess-commits.
6. Report: classification (evidence quoted) · root cause or quarantine ticket · fix + local verification · recurrence risk and prevention. Infra failures get named as infra so reruns don't get normalized.
