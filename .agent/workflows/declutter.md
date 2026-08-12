---
description: Cleanup-only pass applying behavior-preserving simplifications to a diff — no bug fixes, no redesign
---

# /declutter

Argument: what to sweep (defaults to commits ahead of upstream plus uncommitted changes).

## Steps

1. **Net first**: run the tests, record the result. Red → stop (fix via /debug first). No coverage over the diff → say so; only zero-risk subtractions (dead code, unused imports, debug leftovers).
2. **Scope**: the default diff only — cleaning untouched code turns a reviewable diff into archaeology; note pre-existing clutter for a separate pass.
3. **Sweep**, in value order: duplication of what already exists (search before believing new code is new — folding into an existing helper is the top move) · dead weight (unreachable branches, unused vars/imports, commented-out code, debug prints, done TODOs, rolled-out flags) · speculative abstraction (one-implementation interfaces, never-varying config, forwarding layers) · needless indirection (nothing-wrappers, assign-once-use-once, classes that should be functions) · simpler equivalents (guard clauses, stdlib idioms, the boring two lines over the clever one) · free inefficiency fixes (hoist from loop, list→set membership) only when also cleaner — measurement-needing work is /perf.
4. **Stay in your lane**: apply each change, run affected tests as you go. Found a bug → do NOT fix here; report it (deep-review format) — behavior changes hidden in cleanup diffs are the dangerous kind. Needs redesign → /refactor. Don't touch public API surface, commented workarounds, performance-shaped code; unsure if load-bearing → check callers/history, else leave and note.
5. **Prove**: full suite after equals before (quote both). Report removals by category · net line delta · bugs noticed-not-fixed · deliberately left. Nothing found → state what was checked.
6. Never: behavior changes "while here", golfing (fewer chars ≠ simpler), restyling against the file's idiom, trusting "obviously equivalent" without the test run.
