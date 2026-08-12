---
name: pr-workflow
description: Branch-to-merge hygiene that makes changes reviewable — one concern per PR, commits that tell the story, a description that fronts the why and the risk, review turnaround treated as production work. Use when creating branches/commits/PRs, preparing work for review, splitting an oversized change, or responding to review.
---

# PR Workflow

A PR is not a diff dump — it's an argument that a change is safe to merge, made to a reader with limited time. Review quality is a direct function of reviewability: past a few hundred lines, defect-finding falls off a cliff and "LGTM" becomes a measure of fatigue, not safety. Everything here optimizes one variable: **how cheaply can a reviewer (human or model) reconstruct your reasoning and check it?**

## 1. One concern per PR

The unit of review is one deliberate change: a feature, a fix, a refactor — not "the feature, plus a drive-by rename, plus the formatter's opinion of three unrelated files". Mixed concerns force the reviewer to untangle what you already knew, and they make revert a hostage negotiation. The classic splits, which are also the merge order: **refactor-then-behave** (the enabling refactor as its own no-behavior-change PR — reviewable in minutes because tests must pass unchanged — then the small behavior change on top) · **mechanical-then-manual** (the rename/codemod/format touching 400 lines, separately from the 30 lines of judgment) · **expand-then-contract** for anything with a migration (`migrate`). If a PR needs the word "also" in its summary, it wanted to be two.

Size discipline follows automatically: aim under ~400 changed lines of *judgment* code. Generated files, lockfiles, and snapshots get labeled as such so the reviewer knows what not to read.

## 2. Commits are the narrative

Work in whatever order reality demanded; *present* history that reads as a reasoned sequence — each commit one buildable, logical step, because a bisect will land on it and a reviewer may walk them. Messages: subject = the change in imperative mood; body = **why** — the constraint, the bug link, the rejected alternative. The diff already shows *what*; a message that paraphrases the diff ("update handler.py") stores zero information for the archaeologist — and the archaeologist is usually future-you, or a model reconstructing intent from `git log`. Squash the "fix typo/wip/fix again" noise before review; that's presentation, not deception.

## 3. The description is the review's map

Front-load what the reviewer needs *before* the diff makes sense:

- **What & why** — the problem, in one or two sentences, before the solution. Link the issue; don't make the reviewer tab away to learn the context.
- **How, at the design level** — the approach and the alternative you rejected (pre-empts the "why didn't you just…" round-trip).
- **Risk & proof** — what could break, and the `prove`-style evidence it doesn't: tests added, the manual verification you ran, quoted. "How I tested this" is the highest-value section and the most-skipped.
- **A guided tour when the diff is nonlinear** — "start at `router.py`, the rest is plumbing" saves the reviewer the twenty minutes you'd spend on their first confused comment.

Then run the pre-review pass yourself: read your own full diff *as the reviewer* (`deep-review` self-pass), catch the debug print and the stray file, run the suite. Every defect the reviewer finds that you could have is goodwill spent at a bad exchange rate.

## 4. Review is a dialogue with asymmetric stakes

**Responding**: every comment gets a response — *fixed* (with the commit), *pushback* (with reasoning; disagreement is legitimate, silence is not), or *deferred* (with a ticket link, and only for genuinely separable work). Push response-commits without force-push during review, so the reviewer can diff-since-last-look instead of re-reading. Big disagreement threads move to a synchronous channel — comment ping-pong past round three is the most expensive communication medium in software.

**Reviewing**: turnaround is production work — a PR waiting two days costs more than most bugs (context evaporates, conflicts accrete, the author moves on). Review the approach first, mechanics second; a perfect implementation of the wrong design is the worst outcome of a review. Distinguish *blocking* from *preference* explicitly — reviews where every nit reads as a demand teach authors to ship less. The findings discipline is `deep-review`'s.

## 5. Merging

Green CI, resolved threads, up-to-date with base — then merge with the strategy the repo actually uses (squash: the PR title/description become the commit — write them like it; merge/rebase: your commit narrative from step 2 is the history). Delete the branch. The PR's job isn't done until `release` or the deploy pipeline takes custody — a merged-but-undeployed pile is inventory, and inventory rots.

## Anti-patterns

- The 2,000-line "review this" PR — decompose it (`breakdown`) or expect rubber-stamping, which is a review in name only.
- Force-pushing over a reviewer's in-progress review.
- Draft PRs used as CI scratchpads and then "promoted" with the scaffolding commits still in.
- Addressing review comments in the same commit as new feature work.
- "Will fix in a follow-up" with no ticket — the follow-up rate rounds to zero.
- Merging your own PR without review because it's "trivial" — trivial changes take trivial review time, so the exception buys nothing and normalizes the bypass.
