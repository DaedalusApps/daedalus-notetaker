---
name: perf
description: Performance work driven by measurement — profile before touching code, fix the top bottleneck, re-measure, stop at the target. Use when something is slow, uses too much memory, or costs too much; when asked to optimize; or when setting performance budgets.
---

# Perf

Optimization without measurement is shotgun debugging with a different verb. Intuition about where time goes is wrong often enough that acting on it wastes the effort: the bottleneck is an empirical fact, not a code smell. This protocol is a loop — measure, fix the largest proven cost, measure again — with an explicit exit.

## 1. Define done before touching anything

A number, not an adjective: "p95 under 200ms at 100 rps", "import completes in under 10 minutes", "peak RSS under 2GB". Get it from the user or propose one and confirm. Without a target, optimization has no stopping rule and every hour is justifiable. Also fix the *conditions*: which workload, which dataset size, which hardware — a fix that helps the benchmark and misses production conditions is a miss.

## 2. Measure the baseline

Reproduce the slowness on demand and record the number, exactly like reproducing a bug (`debug` step 0). Benchmark hygiene, because noisy measurements produce fictional wins:

- Warm up before timing (JIT, caches, connection pools); time multiple runs; report median and spread, not the best run.
- One variable at a time: same data, same flags, same machine as the comparison run.
- Measure at the level the target is set: end-to-end latency target → end-to-end measurement. Micro-benchmarks answer micro-questions only.

## 3. Profile — locate, don't theorize

Find where the time/memory actually goes: a profiler, or timing probes at the midpoints of the flow (bisection, exactly like localizing a bug). The output you want is a ranked list of costs. Common shapes worth suspecting while you profile, *not* instead of profiling: N+1 queries · work inside a loop that could hoist out · missing index (check the query plan) · sync I/O on the hot path · repeated recomputation of an invariant · accidental O(n²) via nested scans or string concatenation in a loop · chatty round-trips that could batch.

## 4. Fix the top item only

The profile ranks; you take the top. Fixing the #3 item first is how optimization sprees produce complexity without speed. Order of preference for the fix itself:

1. **Don't do the work** — cache an invariant, dedupe requests, early-exit, do it lazily or offline.
2. **Do less work** — better algorithm or data structure, batch the round-trips, index the query, stream instead of materialize.
3. **Do the work faster** — parallelism, lower-level rewrite. Last, because it usually costs the most complexity per unit of speedup.

One change per iteration, or the re-measurement can't attribute the result.

## 5. Re-measure, decide, repeat

Same benchmark, same conditions. Quote before/after. Three outcomes: target met → stop (further optimization is unrequested complexity); improved but short → loop to step 3, the profile has changed; no improvement → **revert** and re-read the profile — a kept non-fix is pure complexity debt.

## Memory and cost

The same loop works when the resource is memory (profile allocations; suspect unbounded caches, accidental copies of large structures, retained references) or dollars (profile the bill; suspect chatty APIs, over-provisioned defaults, unbatched per-item calls). Define the target, measure, rank, fix the top, re-measure.

## Report

Target · baseline (quoted) · profile's top items · change made and why that one · after-measurement (quoted) · target met or remaining gap. Every kept change carries a caching/staleness or complexity note if it added either. Regression guard: where feasible, leave the benchmark script in the repo so the next regression is measurable.

## Anti-patterns

- Optimizing from the code's *looks* — the readable-but-slow-looking loop is usually not the bottleneck.
- Caching as a reflex. Every cache is a staleness bug that hasn't happened yet; it must be justified by the profile and get an invalidation story.
- Benchmarking the best of one run, or the cold run against the warm one.
- Micro-optimizing a path the profile shows at 2%. The theoretical ceiling of that work is 2%.
- Keeping a change that didn't measurably help ("it can't hurt" — it does; it costs review and flexibility forever).
- Trading correctness for speed silently — approximations, staleness windows, and dropped edge cases are product decisions, not optimizations; surface them.

Behavior must survive: run the tests after each change (`refactor`'s net), and close with `prove` — the evidence is the before/after numbers plus a green suite.
