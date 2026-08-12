---
trigger: model_decision
description: Apply when investigating any failure — a bug report, failing test, error message, stack trace, regression, or unexpected output.
---

# Debugging protocol

Debugging is search; every guess-edit grows the search space, every observation shrinks it.

1. **Reproduce first.** Get a failing run you can trigger on demand; record the exact command and output. No edits before a reproduction exists. Not reproducible → switch to evidence-gathering (logs, inputs, environment diff against a working instance), not guess-fixing.
2. **Read the actual error** — whole message, whole stack, the *first* error not the last. Copy the key line verbatim.
3. **State the gap**: "expected A because <contract>; observed B." Can't state the expected behavior → investigate that first.
4. **Localize mechanically** before theorizing: stack-trace line → recent changes (`git diff`, `git bisect`) → binary-search the data flow with one probe at the midpoint → differential against the closest working analog.
5. **Hypothesis loop**: write `H: cause | test | predicted result` → make ONE change → observe → confirmed/refuted. Revert refuted changes immediately. Same fix failing twice = wrong hypothesis, never a third try. Three refuted hypotheses in a row → widen: re-read the code without a theory, build a smaller repro, question one thing assumed good (input, config, the test itself).
6. **Fix the cause.** State the chain root cause → mechanism → symptom before writing the fix. Forbidden: widening catch blocks, deleting failing asserts, `sleep()` for races, making the message disappear instead of the behavior correct.
7. **Prove it**: original repro passes (quote output) · regression test that fails without the fix · neighboring tests still pass · final diff contains only the fix.
