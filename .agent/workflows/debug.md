---
description: Hypothesis-driven debugging — reproduce, localize, one change at a time, fix the cause, prove the fix
---

# /debug

Argument: the failure to investigate (error text, failing test, or description).

## Steps

1. **Reproduce**: run the failing thing; record the exact command and output. No edits before a reproduction exists. Can't reproduce → gather evidence (logs, inputs, environment diff vs. a working instance) and report; don't guess-fix.
2. **Read the error** — whole message, whole stack, the FIRST error not the last. Quote the key line.
3. **State the gap**: "expected A because <contract>; observed B."
4. **Localize** mechanically: stack-trace line → `git diff`/`git log` against last-known-good → binary-search the data flow with one probe at the midpoint → differential against the closest working analog.
5. **Hypothesis loop** (max 3 rounds): write `H: cause | test | prediction` → one change → observe → confirmed/refuted. Revert refuted changes immediately. Same fix failing twice = wrong hypothesis. After 3 refutations: re-read the code with no theory, build a smaller repro, question one "known-good" assumption — then report state honestly if still stuck.
6. **Fix the cause**: state the chain root cause → mechanism → symptom first. No catch-widening, no deleted asserts, no `sleep()` for races.
7. **Prove**: original repro passes (quote it) · add a regression test and confirm it fails with the fix reverted · run neighboring tests · final diff contains only the fix.
8. Report: root cause chain, the fix, quoted proof, regression test, blast radius checked. Refuted hypotheses only if they carry a warning.
