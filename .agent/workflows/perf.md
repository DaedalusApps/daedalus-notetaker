---
description: Measurement-driven performance work — profile, fix the top bottleneck, re-measure, stop at the target
---

# /perf

Argument: what's slow/heavy/expensive, and the target if known.

## Steps

1. **Target**: a number before any code ("p95 < 200ms at 100 rps") plus the workload/conditions; none given → propose and confirm. No target = no stopping rule.
2. **Baseline**: reproduce the slowness, record the number. Hygiene: warm up · ≥3 runs · median and spread, never best-run · identical data/flags/machine for every comparison.
3. **Profile**: profiler or timing probes at flow midpoints → a ranked cost list. Suspect (while profiling, never instead): N+1 queries, hoistable loop work, missing index, sync I/O on hot path, repeated recomputation, accidental O(n²), unbatched round-trips.
4. **Fix the top item only**, one change per iteration. Preference: don't do the work (cache invariants, dedupe, lazy) → do less (better algorithm, batch, index, stream) → do it faster (parallelism — last, highest complexity cost).
5. **Re-measure** same conditions; quote before/after. Target met → stop. Short → back to 3 (profile changed). No improvement → revert; never keep a change that didn't measurably help. Run tests after each kept change.
6. Report: target · baseline · top profile items · change · after-number (quoted) · met or gap. Never cache without an invalidation story; never trade correctness silently; a 2%-of-profile path has a 2% ceiling.
