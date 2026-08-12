---
name: ci-triage
description: Get a red build green for the right reason — read the first real failure, classify it (code, test, flake, infra, drift), reproduce locally, fix or quarantine with a ticket, never retry-until-green as a fix. Use when CI fails, a pipeline breaks, a build goes red, or a test is flaky.
---

# CI Triage

A red build is a message; triage is reading it correctly. The expensive failure mode is treating CI as a slot machine — rerun until green — which converts every real bug and every flake into a permanent tax on the whole team. This protocol classifies first, because the correct action is completely different per class.

## 1. Read the log like a debugger

- Find the **first** failure in the run, not the last — later failures are usually cascade (a failed build step makes every test "fail").
- Read the actual failing output, not just the step name. The decisive line is usually within 20 lines of the first non-zero exit. Copy it verbatim into your notes.
- Check what *changed*: the diff under test, but also the things CI re-resolves every run — dependency versions, base images, runners, external services. "No code changed" never means "nothing changed".

## 2. Classify — the fork in the road

| Class | Evidence signature | Action |
|---|---|---|
| **Code bug** | Fails deterministically, traceable to the diff | Fix via `debug`; the log's first error is your reproduction |
| **Broken test** | Code behavior is right, test's expectation is wrong/stale | Fix the test — but prove the *code* is right first, don't assume it |
| **Flake** | Same commit passes and fails; timing/ordering/network in the trace | Quarantine + ticket (below) — never just rerun and move on |
| **Infra** | Runner died, disk full, network to registry failed, timeout with no test output | Rerun is legitimate *for this class only*; recurring → escalate/ticket |
| **Drift** | New version of a dependency/tool/base image resolved since the last green run | Pin it, then upgrade deliberately via `migrate` |

The discriminating experiment when unsure: rerun the failed job once on the **same commit**. Same failure → deterministic (code/test/drift). Different or gone → flake or infra. That is the only diagnostic rerun; reruns after classification are avoidance.

## 3. Reproduce locally, then fix

Run the failing thing locally with the command CI ran (from the workflow file, not from memory). If local passes while CI fails, diff the environments — versions, env vars, parallelism, timezone/locale, resource limits, test order — that diff *is* the bug's habitat. Fix by class per the table; a fix is proven the way `debug` proves it: the failing run now passes, and you can say why.

## 4. Flakes get quarantined, not tolerated

A flaky test is a real bug in the test or the code — shared state, order dependence, time, unawaited async, port collisions — that hasn't been prioritized. The protocol:

1. Reproduce the flakiness if cheap (`--repeat`/stress-run the single test).
2. If you can fix the root cause now (< the cost of two future red builds), fix it.
3. Otherwise **quarantine**: skip-with-annotation or move to a non-blocking lane, *with a ticket linked in the skip marker*. A skip without a ticket is a deletion with extra steps.
4. Never widen timeouts or add sleeps as the "fix" — that's paying the flake tax forever in runtime.

## 5. Merge-queue / long-red situations

When main is red: fixing forward is fine only if it's minutes away; otherwise revert the breaking change first — an unblocked team outweighs anyone's momentum. When many PRs are entangled, bisect by commit (`git bisect` against the CI command) instead of reasoning about who's guilty. After any main-is-red incident, note what gate would have caught it pre-merge.

## Report

Classification (with the evidence line quoted) · root cause for code/test/drift, or quarantine ticket for flakes · the fix and its local verification · whether the same failure can recur and what would prevent it. One extra sentence when the failure was infra: say so explicitly, so reruns don't get normalized for the other classes.

## Anti-patterns

- Rerunning more than the one diagnostic rerun before classifying.
- Fixing the test to match broken behavior because the diff "must be" right.
- Deleting/skipping a test without a linked ticket and a stated reason.
- `sleep(5)` as a race fix; timeout bumps as a slowness fix.
- Merging on yellow ("only the flaky one failed") without quarantining the flake.
- Debugging CI by pushing guess-commits — reproduce locally or in a scratch branch; every push-debug cycle costs the whole queue.
