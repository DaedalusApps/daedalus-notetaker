---
description: Turn a request into a numbered requirements ledger with acceptance criteria, non-goals, and the riskiest assumption marked
---

# /spec

Argument: the request or feature to specify. If omitted, specify the most recent request in the conversation.

## Steps

1. Extract requirements of three kinds: **explicit** (stated — reread the request line by line), **implicit** (what it assumes: "add login" implies existing users still work), and **negative** (what must NOT change — regressions are violated negative requirements nobody wrote down).
2. Number and classify each as MUST / SHOULD / WON'T. Write at least two WON'Ts — non-goals are the scope fence against both gold-plating and "I assumed you also wanted…".
3. For each MUST, write an acceptance criterion a stranger could check: a command plus expected result, or a concrete scenario plus correct behavior. "Works correctly" is not a criterion. If you can't write one, the requirement isn't understood — investigate the codebase before asking.
4. List each ambiguity that changes the work. Cheap-to-reverse → pick the sensible default, record it as "assuming X; say the word if not". Expensive-to-undo (data model, public API, user-visible) → ask, batching all questions into one round.
5. Mark the riskiest assumption — the one that invalidates the most work if wrong. It becomes the first thing to probe.
6. Output the ledger. For sizable work, write it to `SPEC.md`; for small tasks, keep it inline. Keep statuses current as work proceeds — the final `/prove` walks this ledger.
