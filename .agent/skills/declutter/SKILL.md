---
name: declutter
description: Cleanup-only pass that applies behavior-preserving simplifications to a diff before it ships — duplicate logic folded into existing helpers, dead code removed, needless indirection collapsed. Not a bug hunt and not a redesign. Use as a quality gate before opening a PR, after generated/AI-written code, or when asked to simplify/clean up recent changes.
---

# Declutter

Code grows clutter while being written — the helper that duplicates one that already existed, the abstraction added for a second caller that never came, the debug scaffolding, the six lines that a stdlib call replaces. This is a *sweep-and-apply* pass that removes it while the diff is still cheap to change: unlike `deep-review` it fixes rather than reports, and unlike `refactor` it has no design goal — only subtraction. The one inviolable constraint: **behavior is preserved, proven by the same tests passing before and after.**

## 0. The net

Run the tests before touching anything and record the result. Red suite → stop; declutter only works above a green baseline (fix via `debug` first, or declutter only the files the red tests don't cover). No tests over the diff → say so, downgrade to only the zero-risk subtractions (dead code, unused imports, debug leftovers), and skip anything that restructures logic.

## 1. Scope

Default: the current diff — commits ahead of upstream plus uncommitted changes. Cleaning code you didn't touch turns a reviewable diff into an archaeology project; note pre-existing clutter for a separate pass instead. An explicit argument (file, directory, branch) overrides the default.

## 2. The sweep — what to hunt

In order of value:

1. **Duplication of what already exists** — the diff re-implements a helper, util, or pattern the codebase already has. Search before believing new code is new; folding into the existing one is the single highest-value declutter move.
2. **Dead weight** — unreachable branches, unused variables/params/imports, commented-out code, debug prints, `TODO`s that are done, feature flags fully rolled out.
3. **Speculative abstraction** — interfaces with one implementation, config for values that never vary, hooks with no consumer, layers that only forward. Abstraction is a loan against future callers; no callers, no loan (YAGNI).
4. **Needless indirection** — the wrapper that adds nothing, the variable assigned once and used once (unless it names an unclear expression), the class that should be a function, callback chains a direct call replaces.
5. **Convoluted logic with a simpler equivalent** — nested conditionals → guard clauses; hand-rolled loops → the stdlib/idiom (`sum`, comprehension, `Object.entries`); boolean gymnastics (`if x: return True…`) → the expression itself; clever one-liners → the boring two lines, when the one-liner needs a comment to parse.
6. **Local inefficiency that costs nothing to fix** — the repeated lookup hoisted out of a loop, the O(n²) `in list` that should be a set. *Only* when the simpler form is also cleaner; anything needing measurement or trade-offs is `perf`'s job, not this pass.

## 3. Apply — but stay in your lane

- Apply each simplification, running the affected tests as you go (full suite at the end). One logical cleanup per commit-worthy chunk keeps it revertible.
- **Found a bug while sweeping?** Do not fix it here. Report it in the summary (`deep-review` format) — a behavior *change* hidden inside a "cleanup" diff is the most dangerous kind, because reviewers are skimming for no-ops.
- **Wanted a redesign?** Out of scope. If the module needs restructuring, say so and point at `refactor` — declutter never moves architecture.
- Respect deliberate deviation: performance-shaped code, workarounds with a comment explaining them, and public API surface (removing an unused *public* export is a breaking change — flag, don't cut). When a "pointless" thing might be load-bearing, check callers/history before deleting; if still unsure, leave it and note it.

## 4. Prove and report

Full suite after, same result as before (quote both). Report: what was removed/simplified, grouped by category · net line delta · bugs *noticed but not fixed* (report format from `deep-review`) · anything deliberately left (suspected load-bearing, public surface, needs-`refactor`). If the sweep found nothing, say what was checked — a diff that survives a real sweep clean is worth knowing.

## Anti-patterns

- Fixing bugs or changing behavior "while I'm here" — the cardinal sin of this pass.
- Golfing: fewer characters is not simpler. Optimize for the next reader, not the line count.
- Removing the comment instead of the complexity it apologizes for.
- Restyling to your taste against the file's existing idiom — consistency outranks preference.
- Decluttering unrelated files to make the diff look productive.
- Trusting "obviously equivalent" rewrites without the test run — `any()` vs. a loop differs on empty; short-circuiting differs from `&`; "obviously" is where behavior changes hide.
