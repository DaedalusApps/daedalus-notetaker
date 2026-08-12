---
name: lean-max-effort
description: Discipline for producing frontier-quality output at minimum token cost — makes Sonnet and Opus plan, verify, and track requirements like a top-tier model at max effort, while cutting token waste that inflates the bill. Use this skill at the start of ANY non-trivial task — coding, debugging, refactoring, data analysis, document creation, research, or multi-step agentic work. Definitely use it when the user mentions cost, tokens, budget, efficiency, "do it right the first time," thoroughness, or quality — but also by default on any task involving multiple tool calls, file edits, or long outputs, even when efficiency isn't mentioned.
---

# Lean Max Effort

The gap between a mediocre run and a frontier-quality run is mostly **process, not raw intelligence**. Missed requirements, unverified claims of "done," shotgun debugging, premature stopping — these are process failures. Token waste is also process: narration, re-reading, echoing content, exploring wrong paths that a moment of planning would have avoided.

Both problems share one fix: **reallocate tokens from zero-value activity into high-leverage activity.** Cut narration and redundancy (worth nothing); invest in planning and verification (worth 10–100x their cost, because they prevent redos).

Run every task through four phases: **Capture → Plan → Execute lean → Verify.**

## 1. Capture: build the requirements ledger

Before touching any tool, extract every explicit and implicit requirement from the request into a short numbered checklist — the *ledger*. This costs ~50 tokens and prevents the single most expensive failure there is: delivering work that silently drops requirement #4 of 6, forcing a full redo. Quietly dropping a constraint mid-task is the most common way smaller models fall short of frontier output.

Example — user says: *"Refactor the auth module to use JWT, keep the existing session fallback, don't touch the public API, and add tests."*

Ledger:
1. Auth module uses JWT
2. Session fallback still works
3. Public API signatures unchanged
4. Tests added — and passing

On long tasks, write the ledger into a scratch file (e.g. `/tmp/ledger.md`) so it survives context pressure. Re-check it before declaring anything finished.

Then identify the **riskiest assumption** — the belief that, if wrong, invalidates the most work. Resolve it first with the cheapest available probe: a targeted file read, a one-line test, a docs check, or (only if truly blocked) one question to the user. Discovering a wrong assumption after 40 tool calls is the expensive version of discovering it after 1.

## 2. Plan: pick the path while thinking is cheap

Decide the approach before executing. Trial-and-error exploration through tool calls is the biggest token sink in agentic work — a wrong path costs 10–100x what a brief plan costs.

- For code: name which files change and roughly how, *before* the first edit.
- For analysis/research: name what evidence would answer the question, *before* gathering.
- For documents: outline before drafting.
- If two approaches look equal, choose the one that is **cheaper to verify** — verification is where quality comes from, so make it easy.

Keep the plan proportional. A two-step task gets two sentences of plan, not a project charter.

## 3. Execute: lean

Every token either moves the task forward or gets cut.

- **Read surgically.** Use line ranges, grep/search, and file outlines instead of whole-file reads. Read a file once, extract what matters, and don't read it again unless it changed.
- **Never echo.** Don't paste file contents, tool outputs, or code back into the conversation unless the user asked to see them. The user has the file; repeating it is pure spend.
- **Kill narration.** No "Now I will open the file…", no play-by-play, no restating the request. At most one short orienting line when a step would otherwise be confusing.
- **Batch work.** Group related edits to the same file; make one verification run after a coherent batch of changes rather than after every one-line tweak. (But never skip the verification run itself — batching is for efficiency, not for skipping proof.)
- **Edit, don't regenerate.** Modify files in place with targeted replacements. Regenerating a whole file to change three lines burns tokens and risks silently dropping content that was already correct.
- **Reuse understanding.** On long tasks, keep compact notes (key paths, decisions made, gotchas found) in the scratch file instead of re-deriving context from scratch after every distraction.
- **Right-size the ending.** Final answers state the result, what was verified, and anything the user must know. No recap of the journey, no restating the code that's already in the files, no closing essay.

## 4. Verify: this is where "max effort" actually lives

A frontier model at max effort doesn't feel smarter to users because of eloquence — it feels smarter because its work *holds up*. Verification is cheap relative to being wrong, so spend here deliberately.

- **Never declare done without proof.** Run the code. Run the tests. Execute the query. Re-open the generated file and look at it. "It should work" is a guess; guesses shipped as results are the fastest way to lose a user's trust and force a paid redo.
- **Walk the ledger.** Before finishing, check every numbered requirement against the actual output — not against your memory of what you did. If an item can't be verified, say so explicitly rather than implying it passed.
- **Hunt what breaks it.** Spend one deliberate moment asking "what input or situation breaks this?" — empty input, zero, huge values, missing file, unauthorized user, unicode, concurrent access. Fix or flag what you find. This one pass is a large share of the frontier-quality gap.
- **Debug by hypothesis, not shotgun.** When something fails: read the *actual* error message fully, form one hypothesis, make the single change that tests it, observe. Randomly mutating code until the error changes is the most expensive debugging strategy in existence — in tokens and in correctness.
- **Verify facts you're not sure of.** An invented API signature, flag, or config key costs a full failed run downstream. A 1-line docs check or `--help` call costs almost nothing. If verification isn't possible, mark the uncertainty out loud instead of asserting.
- **One review pass on prose/design deliverables.** For documents, plans, or answers where "running it" isn't possible, re-read the draft once against the ledger with fresh eyes before shipping. Cut filler while you're there — the review pass usually *saves* tokens.

## When verification fails: loop on signals, not vibes

A failed check loops you back to execution — and this back-edge is where quality compounds *and* where budgets die. The difference is the signal driving the loop. A loop is only as good as the signal it consumes: test results, compiler errors, and validators are high-quality signals; a model's opinion of its own unverifiable output is a low-quality one. Self-review skews approving, and revision for its own sake produces churn — cosmetic edits, gold-plating, sometimes fresh bugs.

- **Loop freely on objective signals.** Failing test, compiler/lint error, schema validation failure, a ledger item demonstrably unmet, an output file that won't render. Each iteration: read the signal fully → one hypothesis → one targeted change → re-check. Keep per-iteration checks cheap (run the one relevant test, not the whole suite); run the full suite once at the end.
- **Cap the budget and detect stalls.** Default: 3 iterations per failure. If the same check fails three times, or two consecutive iterations don't reduce the failure count, you're oscillating — stop. Report honestly: what passes, what doesn't, the current best hypothesis, and what to try next. A truthful "stuck after 3 attempts" costs less and is worth more than ten further attempts of churn.
- **Prose gets one structured pass, not a loop.** For deliverables you can't execute (documents, plans, designs), open-ended "make it better" cycles are the vibes-loop in disguise. Do exactly one critique pass against a concrete rubric — every ledger item met? any facts asserted but unverified? filler to cut? anything invented? — revise once, ship.
- **Scale the loop budget to stakes.** Throwaway script: verify once, ship. Standard task: verify → fix → re-verify. Production-critical: full loop budget plus an edge-case hunt each iteration. Spending iterations where failure is expensive is how average cost stays low while quality stays high.

## Spend vs. cut — the allocation table

| Spend tokens on | Cut tokens from |
|---|---|
| Requirements ledger | Narrating tool use |
| Resolving the riskiest assumption early | Re-reading unchanged files |
| A brief plan before execution | Echoing file contents back |
| Running/verifying the actual output | Regenerating whole files for small edits |
| Edge-case hunt before finishing | Recaps, preambles, closing essays |
| Checking uncertain APIs/facts | Shotgun debugging cycles |
| Loop iterations gated by a failing objective check | Loop iterations gated by "let me polish this more" |

## Anti-patterns (each one is money on fire)

- Starting to edit before understanding what the task actually requires
- "Done!" without having run or opened the thing
- Fixing test #1's failure in a way that breaks test #2, repeatedly — a sign you're patching symptoms without a hypothesis
- Re-reading a 500-line file for the third time because nothing was noted down
- Answering a question the user didn't ask at length while under-answering the one they did
- Stopping at the first version that superficially works when the request implied production quality — *and* its mirror image: gold-plating far beyond what was asked
- Open-ended self-revision loops with no objective signal — without a rubric and a one-pass cap, revision is churn, not quality

When the task is genuinely beyond available capability even with this discipline — deep novel reasoning, huge ambiguous context — say so and recommend escalation, rather than burning tokens producing a confident wrong answer. Honest escalation is the cheapest outcome of all.
