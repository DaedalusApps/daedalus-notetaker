---
name: debug
description: Hypothesis-driven debugging — reproduce first, localize by bisection, test one hypothesis at a time, fix the cause not the symptom, prove the fix. Use whenever anything fails — bug report, failing test, error message, stack trace, regression, flaky behavior, or "it worked yesterday". Applies equally to failing builds, configs, queries, and pipelines.
---

# Debug

Debugging is a search problem. The bug is in a finite space; every observation shrinks the space, every guess-edit grows it. Shotgun debugging — mutating code until the error changes — is a random walk through that space and the most expensive strategy in existence, in tokens and in correctness. This protocol replaces it with: evidence → hypothesis → cheapest discriminating test → observe.

## 0. Reproduce, or switch modes

Get a failing run you can trigger on demand, and record the exact command plus the exact failing output. A bug you cannot reproduce is a bug you cannot prove fixed.

If it will not reproduce (flaky, prod-only, "sometimes"): do **not** guess-fix. Switch to evidence mode — collect the failing instance's logs, timestamps, inputs, and environment; diff them against a succeeding instance; add targeted logging at the suspected boundary and wait for the next occurrence. Flakiness is itself a diagnosis: suspect ordering, shared state, time, or concurrency.

## 1. Read the actual error

The full message, the full stack, and the **first** error in the output — later errors are usually cascade. Error text is the densest free evidence you will ever get; most failed debugging sessions begin with skimming it. Copy the load-bearing line into your notes verbatim.

## 2. State expected vs. observed

One sentence: "X should produce A because ⟨contract⟩; it produces B." If you cannot state what *should* happen precisely, that is the first thing to investigate — you are not debugging yet, you are still specifying.

## 3. Localize before theorizing

Shrink the search space mechanically before spending effort on theories:

- **Stack trace / error location** — often localizes for free. Start there.
- **Recency** — `git diff` / `git log` against last-known-good. Most bugs live in the most recent change. `git bisect` when the regression window is long.
- **Binary search the flow** — place one probe (log, print, breakpoint, intermediate assert) at the midpoint of the data path: is the data still correct here? Halve, repeat.
- **Differential** — find the closest working analog (a passing sibling test, the same call that works elsewhere) and minimize the difference until only the culprit remains.

## 4. Hypothesis loop

Keep a short ledger (scratch file on long hunts — it stops you from re-testing refuted ideas):

```
H1: <cause> — test: <observation that discriminates> — predict: <result if true> — actual: <result> → refuted/confirmed
```

Rules of the loop:

- **One hypothesis, one change, one observation at a time.** Two simultaneous changes make the result unattributable.
- **Predict before you look.** A wrong prediction means your model of the system is wrong — update the model before the next change, don't just try another patch.
- The test must **discriminate**: it would come out differently if the hypothesis were false. "Add a fix and see if the error goes away" discriminates poorly — errors can vanish for the wrong reason.
- **Revert dead ends immediately.** Leftover experimental edits poison every later observation.
- The same fix failing the same way twice means the hypothesis is wrong. Never try it a third time.
- Three refuted hypotheses in a row → widen the frame: re-read the code around the failure without a theory, build a smaller reproduction, and question one thing you have been treating as known-good (the input, the config, the environment, the test itself).
- "That's impossible" means one of your assumptions is false. List the assumptions the impossibility rests on; test the most load-bearing one.

## 5. Fix the cause

Before writing the fix, state the full causal chain: root cause → mechanism → observed failure. If the fix works but you cannot explain the chain, you have not fixed the bug — you have relocated it. Symptom patches (broadening a catch block, adding a null check that hides the real question, `sleep()` around a race) fail this test.

## 6. Prove the fix

- The original reproduction now passes — run it, quote the output.
- **Add a regression test that fails without the fix.** If you never saw it fail, you don't know it can.
- Check the blast radius: who else calls the changed code? Run the neighboring tests, not just the new one.
- Remove every probe and experimental edit; diff the final change and confirm it contains only the fix.

Then finish with the `prove` skill's verification pass if the task is larger than the fix.

## Report format

Root cause (the causal chain, 1–3 sentences) · the fix and why it addresses the cause · proof (repro before/after, quoted) · regression test added · blast radius checked. No narration of the journey — refuted hypotheses are only worth mentioning if they carry a warning for future work.

## Anti-patterns — each one lengthens the hunt

- Editing before reproducing.
- Skimming the error and pattern-matching to a familiar bug ("probably a cache thing").
- Two changes per iteration.
- Making the error disappear instead of making the behavior correct — swallowing exceptions, deleting the failing assert, loosening the test.
- Blaming the compiler, framework, or library first. It's you until proven otherwise — and when it truly is the dependency, prove it with a minimal repro outside your code.
- Leaving debug prints, commented-out experiments, or disabled tests in the final diff.
