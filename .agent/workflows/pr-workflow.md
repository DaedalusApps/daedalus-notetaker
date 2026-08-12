---
description: Branch-to-merge hygiene — one concern per PR, narrative commits, risk-fronting description, disciplined review response
---

# /pr-workflow

Argument: the change to prepare for review (defaults to the current branch).

## Steps

1. **One concern per PR**: a feature, a fix, or a refactor — never "the feature plus a drive-by rename plus reformatting". Splits (and merge order): refactor-then-behave (enabling refactor as its own no-behavior-change PR) · mechanical-then-manual (the 400-line codemod apart from the 30 judgment lines) · expand-then-contract for migrations. A summary needing "also" = two PRs. Label generated files/lockfiles. Aim <~400 changed lines of judgment code.
2. **Commits as narrative**: each one buildable logical step (bisect lands on it); subject imperative; body = WHY (constraint, bug link, rejected alternative) — the diff already shows what. Squash wip/fix-typo noise before review.
3. **Description**: what & why in 1–2 sentences before the solution (link the issue) · the approach + the rejected alternative · **risk & proof** — what could break and the evidence it doesn't (tests added, verification quoted; the most-skipped, highest-value section) · a guided tour when the diff is nonlinear. Then self-review your own full diff as the reviewer (/deep-review self-pass), remove debug leftovers, run the suite.
4. **Review dialogue**: every comment answered — fixed (with commit) / pushback (with reasoning) / deferred (with ticket link). No force-push during review; response commits stay separate from new feature work. Reviewing others: approach before mechanics; blocking vs preference marked; turnaround treated as production work.
5. **Merge**: green CI, resolved threads, up to date with base. Squash-merge → the PR title/description become the commit; write them like it. Delete the branch; merged-but-undeployed is rotting inventory.
6. Never: the 2,000-line "review this" (decompose via /breakdown) · force-pushing over an in-progress review · "will fix in a follow-up" without the ticket · self-merging "trivial" changes.
