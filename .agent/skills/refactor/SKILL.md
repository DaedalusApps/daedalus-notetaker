---
name: refactor
description: Behavior-preserving refactoring protocol — establish a test net first, transform in small verified steps, never mix structure changes with behavior changes. Use when restructuring, extracting, renaming, moving, de-duplicating, or modernizing code, or when asked to "clean up" working code.
---

# Refactor

A refactor's contract is absolute: observable behavior identical, structure better. A behavior change hidden inside a refactor is the most expensive kind of diff there is — reviewers approve it as "just cleanup", and the regression surfaces weeks later with no obvious cause. The whole protocol exists to keep that contract.

## 1. Build the net first

Decide what will prove behavior is preserved *before touching anything*:

- Existing tests covering the target — run them, record green (this is the baseline; quote the summary line).
- Coverage thin or absent? **Write characterization tests first**: pin the *current* behavior with tests, including current quirks and bugs. A bug you find during this step gets pinned and logged, not fixed — fixing it mid-refactor breaks the contract. Fix it in a separate change after.
- No practical way to test? Shrink the step size until each step is trivially inspectable, and say out loud that the net is inspection-only.

Mark a clean rollback point (commit or stash) before the first transformation.

## 2. Keep the compatibility ledger

List what must not change: public signatures, exports, serialized formats, DB schemas, CLI flags, URLs, error messages other code matches on, timing/ordering other code depends on. Check the list at the end. If a public interface *must* change, that is not a refactor — split it into its own change and label it as breaking.

## 3. Transform in small named steps

- One transformation type at a time: rename, extract function/module, inline, move, replace-conditional, dedupe. Each step has a name; if you can't name the transformation, you're rewriting, not refactoring.
- **Run the net after every step or small coherent batch.** Green → proceed. Red → revert the step and re-approach; never debug forward through a half-applied refactor, because you can no longer tell which step broke it.
- Prefer mechanical moves: tool-assisted renames, exact search-and-replace. Then grep for stragglers the tools miss — strings, docs, configs, reflection, serialized names.
- "While I'm here" fixes are the classic contract violation. Log every tempting improvement to a follow-up list; touch nothing outside the named transformation.

## 4. Finish clean

- Net green on the final state — full relevant suite, quoted.
- Ledger walked — every must-not-change item confirmed unchanged.
- Read the final diff hunk by hunk: every hunk is structural. Any hunk you must explain with "and this also fixes/improves…" gets pulled out into a separate change.
- Deliver the follow-up list separately (found bugs, tempting improvements, further refactors) — that's the refactor's second product.

## Scope control

Refactors grow: each extraction reveals two more candidates. When scope creeps, land what is currently done-and-green, then replan the remainder as its own task (`breakdown` skill for big ones). A merged half of a refactor is worth infinitely more than an unmerged whole.

Also ask whether the refactor is worth doing at all: structure serves the next change. Refactoring code nobody will touch again is churn — it consumes review budget and git-blame clarity and returns nothing.

## Anti-patterns

- Refactor and feature/bugfix in one change — the cardinal sin; it makes both unreviewable.
- Refactoring without a net, on the theory that "I'll be careful". Careful reading is not a net.
- Big-bang rewrite of a module when incremental steps existed. If incremental truly is impossible, that's a rewrite — different risk class, plan it as one (`architect`).
- "Improving" behavior mid-refactor — even obvious bugs get pinned, noted, fixed separately.
- Style-only churn that consumes review attention and blame history without structural gain.

When done, run `prove` if the refactor was part of a larger task; use `deep-review` on the diff when the refactor touched anything load-bearing.
