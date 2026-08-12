---
name: deep-review
description: Adversarial code review that reports only verified, severity-ranked findings with concrete failure scenarios — no speculation, no style-nit flooding. Use when asked to review code, a diff, a branch, or a PR; before merging significant work; or to attack your own completed work with fresh hostility.
---

# Deep Review

A review's value is real defects found minus false alarms raised. An unverified finding has *negative* value — it spends the author's trust and time on a ghost. A style nit has near-zero value. So this protocol does one thing: hunt real defects, verify each one against the actual code before reporting it, and rank what survives.

## 1. Scope and stakes

Review the *change*, not the codebase. Default scope when none is given: the branch's commits ahead of its upstream **plus** uncommitted changes in the working tree — that is "the work being shipped". An explicit target (files, PR, branch, ref range) overrides it. Diff first, then read enough surrounding code (callers, callees, the contract of the modified functions) to judge the change in context. Set depth by stakes — auth, payments, data migration, and concurrency get the full hunt; a README fix gets a proportional glance.

If the repo has a `REVIEW.md` (or review guidance in `CLAUDE.md`), read it first and let it recalibrate severity, skip-paths, and repo-specific checks — the repo's definition of "Important" outranks the defaults below.

## 2. Understand before attacking

Trace the main path of the change end-to-end once, and state its intent in one sentence ("this makes retries idempotent by keying on request-id"). If you cannot write that sentence, you do not understand the change well enough to review it — read more or say so. Check the stated intent against the ticket / commit message: **does the change actually do what it claims?** Requirement mismatch is the highest-yield defect class and the one tests never catch.

## 3. The hunt

Work these categories in order — they are ranked by real-bug yield:

1. **Claim vs. behavior** — requirements silently dropped, edge of the ticket unhandled, behavior change beyond what was asked.
2. **Failure paths** — at every external boundary (I/O, network, parse, user input, subprocess): what happens on error, empty, nil, timeout, partial result? Unhandled failure paths outnumber logic bugs in real code.
3. **State and concurrency** — shared mutable state, check-then-act races, missing idempotency on retried operations, transaction boundaries, ordering assumptions.
4. **Boundaries** — off-by-one, first/last/empty/single/huge, unicode and encoding, timezone/DST, float precision, integer overflow.
5. **Security** — injection (SQL/shell/path/template), authorization checked on *every* path not just the front door, secrets in logs or errors, unsafe deserialization, SSRF on user-supplied URLs.
6. **Resource lifecycle** — unclosed handles, unbounded caches/queues, missing timeouts, retries without backoff, leaks on the error path specifically.
7. **Interface drift** — callers not updated, serialized-format or API contract broken, migration missing for persisted data, dead code left behind.
8. **Test honesty** — would the tests fail if the fix were reverted? Tests that mock the thing under test, assert nothing, or test the framework are defects too.

## 4. Verify every candidate — the gate

Before a finding may be reported, trace the actual failure path in the actual code and construct the concrete triggering scenario: *"call X with an empty list → loop body never runs → `total` stays None → line 88 raises TypeError"*. Then label it:

- **CONFIRMED** — you traced the path and can state the triggering input/state.
- **PLAUSIBLE** — you could not fully trace it (missing context, dynamic dispatch) but can say precisely what would confirm it.

If you cannot articulate a failure scenario, it is not a finding — it is a vibe. Discard it. This gate is what separates a review worth reading from linter noise.

## 5. Report

```
SEVERITY [CONFIRMED|PLAUSIBLE] path:line — defect — failure scenario — suggested fix
```

- **BLOCKER** — wrong results, data loss, security hole, crash on realistic input.
- **MAJOR** — real failure under realistic-but-less-common conditions; correctness debt that will bite.
- **MINOR** — works, but fragile, misleading, or needlessly expensive.

A verified bug that the diff did **not** introduce gets reported too — appended `(PRE-EXISTING)` and listed after the diff's own findings, so the author isn't blamed for it and it isn't lost. Most severe first. Cap at ~10 findings — beyond that, signal drowns; pick the ones that matter and say "further minor issues omitted". Separate "must fix before merge" from "consider". Style feedback gets at most one line total ("consider running the linter — naming and spacing are inconsistent") unless the style issue *hides a bug*.

If something is done well and load-bearing — a subtle lock order, a deliberate off-spec behavior — say so in one line, so a later editor doesn't "fix" it.

## 6. Calibrate the clean report

Zero findings on a nontrivial change usually means a shallow pass, not clean code. Before reporting clean, re-check categories 1–3 explicitly. A clean report must state what was checked ("traced both retry paths; tried empty/huge inputs mentally against the parser; checked callers of the renamed function") — "LGTM" without coverage is not a review.

## Rules

- Review the current files, not your memory of them; re-read after any fix lands.
- Review the code, not the author. Findings name code paths, never competence.
- Never rewrite the change your way as "review" — judge the approach taken; propose a different approach only when the taken one has a defect, and say what the defect is.
- Tests passing is evidence, not proof — tests only cover what someone thought to test. Say what the tests *don't* cover when it matters.
- Self-review counts only after a context break — attack your own work as if a stranger wrote it, from the files on disk.

After fixes land, run `prove` on the fixed state; for independent fresh-context review, use the `code-reviewer` subagent. Cleanup opportunities the hunt turns up (duplication, dead code, needless indirection) are one summary line here, not findings — the apply-fixes sweep for those is `declutter`.
