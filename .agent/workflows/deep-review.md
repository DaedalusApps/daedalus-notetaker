---
description: Adversarial review of a diff, branch, or recent work — verified, severity-ranked findings only
---

# /deep-review

Argument: what to review (defaults to the branch's commits ahead of upstream plus uncommitted changes).

## Steps

1. Scope: the default diff above, or the named target; then read enough callers/callees to judge the change in context. Depth scales with stakes — auth, payments, migrations, concurrency get the full hunt. If the repo has a `REVIEW.md`, its severity rules and skip-paths override the defaults.
2. Trace the main path end to end; state the change's intent in one sentence; check it against what the code actually does.
3. Attack, in order of real-bug yield — write down at least 5 candidate findings before concluding anything: dropped requirements · unhandled failure paths at each external boundary · state/races/idempotency/transactions · off-by-one and boundary values · injection, authz on every path, secrets in logs · resource leaks on error paths, missing timeouts · callers not updated, contracts broken · tests that would pass with the fix reverted.
4. Verify each candidate: trace the actual path, construct the concrete trigger. No concrete failure scenario → discard. Label CONFIRMED or PLAUSIBLE (+ what would confirm).
5. Report `SEVERITY [CONFIRMED|PLAUSIBLE] path:line — defect — trigger — fix`, most severe first (BLOCKER / MAJOR / MINOR), cap ~10, style feedback one line max. Verified bugs the diff didn't introduce: append "(PRE-EXISTING)", list after the diff's own findings. Note load-bearing good decisions a later editor might "fix".
6. If clean: re-check the top three categories first, then state what was checked — a clean report without coverage is not a review.
