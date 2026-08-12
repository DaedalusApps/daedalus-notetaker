---
name: spec
description: Turn a request into a numbered requirements ledger with acceptance criteria, explicit non-goals, surfaced ambiguities, and the riskiest assumption marked. Use at the start of any non-trivial task, when requirements feel fuzzy or contradictory, when scoping a feature, or when asked to write a spec or requirements doc.
---

# Spec

Most failed tasks fail at the spec: a requirement silently dropped, an implicit expectation never surfaced, a constraint invented that nobody asked for. The ledger is the fix — the numbered contract that execution builds against and `prove` verifies against. Everything downstream inherits its quality.

## 1. Extract every requirement — three kinds

- **Explicit** — what the request states. Reread the request line by line; requirement #4 of 6 is the one that gets dropped.
- **Implicit** — what the request assumes: "add login" implies existing users still work, passwords aren't logged, the session survives refresh. Implicit requirements are where "technically did what was asked" disappoints.
- **Negative** — what must NOT change: current behavior, API contracts, performance, other features. Regressions are violated negative requirements nobody wrote down.

## 2. Number and classify

```
MUST   1. <requirement>
       2. <requirement>
SHOULD 3. <requirement — do if cheap, say if skipped>
WON'T  4. <explicitly out of scope — and why>
```

Write at least two WON'Ts. Non-goals are the scope fence: they prevent gold-plating on one side and "I assumed you also wanted…" on the other. A spec with no non-goals hasn't decided what it is.

## 3. Acceptance criterion per MUST

Each MUST gets an observable check a stranger could run: a command plus its expected result, a concrete scenario plus the correct behavior. "Works correctly" is not a criterion; "`pytest tests/test_auth.py` exits 0" and "expired token → 401 with `TOKEN_EXPIRED` body" are. **If you can't write the criterion, you don't understand the requirement yet** — that discovery, made now, is the spec paying for itself.

## 4. Surface ambiguities — decide or ask, deliberately

List each reading of the request that would change the work. For each, either:

- **Default and flag**: pick the sensible reading, record it — "assuming soft-delete, since the admin UI has a restore button; say the word if hard-delete" — and proceed. Right for cheap-to-reverse guesses.
- **Ask**: only when the wrong guess is expensive to undo (data model, public API, anything users see). Batch the questions into one round; dribbling questions one at a time burns everyone's time.

An ambiguity resolved silently in your head is a future dispute; resolved on paper it's a decision.

## 5. Mark the riskiest assumption

Which single assumption, if wrong, invalidates the most work? An API that may not exist, a data shape unconfirmed, a permission you may not have. Mark it — it becomes the first thing to probe during execution (`breakdown` orders tasks by it; `lean-max-effort` resolves it before building).

## 6. Keep the ledger alive

The ledger is a working document, not a ceremony: statuses update as work proceeds (`DONE — evidence`, `IN PROGRESS`, `BLOCKED — on what`), it travels in handoffs (`handoff` skill embeds it), and the final walk-through against actual outputs is the core of `prove`. A ledger written once and never consulted again caught nothing.

## Size discipline

The format scales down: a three-line request gets a three-item inline ledger (thirty seconds, still catches the dropped requirement). Only real projects get a `SPEC.md`. Matching ceremony to stakes is part of the skill — a project charter for a bugfix is its own kind of failure.

## Anti-patterns

- Restating the request in fancier words and calling it a spec — extraction means finding what *isn't* written.
- Acceptance criteria that only the author can evaluate ("clean", "robust", "intuitive").
- Asking the user to resolve ambiguities the codebase already answers — read first, ask second.
- Specifying the solution instead of the requirement ("use Redis" when the requirement is "survives restart") — unless the solution genuinely is a constraint.
- Treating SHOULDs as MUSTs under time pressure — the classification exists precisely for the moment scope must shrink.
