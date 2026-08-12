---
name: prove
description: Prove work actually works before calling it done — strongest available evidence per claim, edge-case hunt, regression check, verbatim output quotes, and honest "I verified / I did not verify" verdicts. Use before reporting any task complete, before commits and handoffs, after fixes, and whenever the user asks "does it work?".
---

# Prove

"Should work" and "works" are different products, and the gap between them is where user trust dies. Every completion claim is a debt; only observed evidence retires it. Verification is also the highest-ROI token spend there is — a check costs tens of tokens, an undetected failure costs a full redo plus the user's confidence.

## 1. List the claims

Write down what "done" is claiming, explicitly and implicitly: every item of the requirements ledger, plus the silent claims that ride along — *it compiles; existing behavior is unchanged; the error path I added actually fires; the config parses*. Each claim needs its own evidence. Claims nobody listed are the ones that ship broken.

## 2. Choose the strongest proof available — per claim

In descending order of strength:

1. **Run the real flow end-to-end** — the actual command, app, endpoint, or pipeline with realistic input, exercising the changed behavior. This is the gold standard; prefer it whenever it exists.
2. **Targeted automated test** — proves the unit, not the wiring. Strong, but say what it doesn't cover.
3. **Build / typecheck / lint** — proves absence of one error class only. Never sufficient alone for a behavior claim.
4. **Static trace** — reading the code path when execution is impossible. Weakest; must be labeled as unexecuted reasoning, never presented as tested.

Using a weaker level when a stronger one was available is the verification anti-pattern: "tests pass" while the app was never launched, "it typechecks" while the query was never run.

## 3. Execute and capture

Actually run the checks — after the **final** edit; any edit invalidates all prior proof. Quote the decisive output line verbatim (test summary line, HTTP status, computed value), with the command that produced it. For visual work, look at the render/screenshot — UI declared done unseen is unverified. Paraphrased success ("tests passed") without the quoted line is how imagined success gets reported.

## 4. Hunt the edges — one deliberate pass

Ask "what input or situation breaks this?" and actually try the top three most likely for this change: empty/zero/negative, huge, unicode, missing file, denied permission, concurrent access, offline dependency, wrong type, duplicate submission. Fix or flag what breaks. This single pass covers a large share of the frontier-quality gap.

## 5. Check for regressions

- The tests that existed before still pass — run the relevant suite, not only the new test.
- Poke the nearest neighbor once: the feature adjacent to the change still behaves (the fastest manual smoke test that would catch collateral damage).
- Diff the final change set: only intended edits, no debug prints, no disabled tests, no drive-by edits.

## 6. Non-executable deliverables

Documents, plans, configs, schemas — proof still exists:

- Walk the ledger against the artifact item by item, checking the artifact, not your memory of writing it.
- One adversarial re-read: anything asserted but unverified? anything invented? anything an informed reader would catch?
- Validate what has a validator: `--dry-run`, `--check`, schema validation, `terraform plan`, JSON/YAML parsing. A config that never parsed is a bug you shipped.

## 7. Deliver the verdict

Use exactly these phrases — they force precision and the reader relies on them:

- **"I verified X"** — followed by the evidence (command + quoted output).
- **"I did not verify Y"** — followed by the reason and what verifying would take.

A partial-but-honest verdict ("core flow verified; concurrent access not tested") beats a confident blanket "everything works" every time. If a check fails, do not soften it or silently patch: report it, fix via the `debug` protocol, then re-prove — the failed-then-fixed cycle is normal; hiding it is not.

## Proof levels by stakes

- **Throwaway script** — run it once on real input. Done.
- **Standard change** — real-flow run + edge pass + relevant existing tests.
- **Production-critical** — all of the above, plus independent verification by the `verifier` subagent with fresh context, plus the edge pass on the *error* paths.

## Rules

- Evidence comes from observation after the last change — never from memory of having written the code.
- A check that cannot fail proves nothing: if a new test never failed, revert the fix once (or mutate the code) to watch it fail, then restore.
- The happy path is half a verification; the claims most likely to be false live on the error paths.
- Don't gold-plate: verify the claims made, at the stakes given. Proving a throwaway script handles unicode filenames is spend without return.
