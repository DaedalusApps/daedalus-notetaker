---
description: Prove the work actually works before calling it done — strongest evidence per claim, edge hunt, regression pass, honest verdict
---

# /prove

Argument: the claim or task to verify (defaults to the most recent "done" claim).

## Steps

1. List the claims: every requirement of the ledger/request, plus the silent ones — it builds; existing behavior unchanged; the new error path actually fires; the config parses.
2. For each claim pick the strongest available proof: run the real flow end-to-end with realistic input → targeted test → build/typecheck/lint (never sufficient alone for behavior) → static trace (label as unexecuted reasoning). Using a weaker level when a stronger exists is the anti-pattern this workflow kills.
3. Execute after the final edit — any edit voids earlier proof. Quote each decisive output line verbatim with its command.
4. Edge hunt: actually try the 3 most likely breakers for this change — empty/zero/negative, huge, unicode, missing file, denied permission, concurrent, offline dependency, duplicate submission. Fix or flag.
5. Regression pass: pre-existing relevant tests still pass (quote the summary); poke the nearest neighboring feature once; diff contains only intended changes — no debug prints, no disabled tests.
6. Verdict, in exactly this language: "I verified X — <command> → '<quoted line>'" / "I did not verify Y — <reason> — <what it would take>". Every claim appears in one list. A failed check is reported, fixed via `/debug`, then re-proven from step 3.
