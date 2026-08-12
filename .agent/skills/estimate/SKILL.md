---
name: estimate
description: Estimates with stated uncertainty instead of theater — decompose, size against comparable past work, widen for the unknowns you can name and the ones you can't, give a range with assumptions attached. Use when asked how long/how big/how much effort something is, when scoping or prioritizing work, or when a deadline is being negotiated.
---

# Estimate

An estimate is a probability distribution that gets communicated as a number — and everything that goes wrong follows from pretending otherwise. Single-number estimates are read as commitments; optimistic ones are read as competence. The honest product is a **range plus the assumptions that would break it**, sized against evidence rather than hope. The goal is not to be exactly right — it's to be *usefully calibrated*: wrong by a known, bounded amount.

## 1. Estimate the right thing

Before sizing, pin what "done" means — estimate against a `spec`-grade definition, not a title. "Add export" is 2 days for CSV-happy-path and 3 weeks for all-formats-with-permissions-and-tests. If the scope is fuzzy, the estimate's job is to *surface that*: give per-interpretation numbers ("CSV only: X; full: Y") — the gap between them is the most useful information the requester will get, because it forces the scope decision now instead of mid-build. And include the whole cost: review, tests, docs, deploy, the migration, the coordination — the code is routinely the minority of it.

## 2. Decompose, then size parts by reference

Break into components small enough that each resembles something *you have actually done before* — reference-class sizing ("the last three endpoints like this took ~2 days each") is the only consistently calibrated method; raw intuition about novel wholes is the least. While decomposing, mark each part **known** (done it before), **variable** (done similar; details differ), or **unknown** (never done it / depends on something unexamined). This map is worth more than the total, because the unknowns are where the estimate will die.

## 3. Attack the unknowns before padding them

An unknown sized by guessing gets a wide guess; an unknown sized by a **spike** — a timeboxed probe (read the API's docs, prototype the tricky join, measure the data volume) — often collapses to a known for an hour's cost. For unknowns that stay unknown, estimate the *discovery*, not the work: "2 days to determine feasibility, then re-estimate" is honest; "1 week for the unknown part" is a number-shaped shrug. Cheapest of all: say when you *can't* usefully estimate yet — "after the spike" beats a fabricated range.

## 4. Correct for the known biases

- **The planning fallacy is the default**, not a personal flaw: best-case components compose into an impossible whole, because everything must go right simultaneously. Your gut estimate *is* the optimistic bound — treat it as the bottom of the range, not the middle.
- **Check actuals**: your last few "2-day" tasks — what did they take? That personal ratio is the highest-value calibration data you own; apply it.
- **Integration and iteration are line items**: parts that each work take real time to work *together*, and the first version will be reviewed, rejected, and revised. If the decomposition has neither, it's a fantasy.
- **A deadline is not an estimate.** When the date is fixed, the honest conversion is scope-shaped: "by the 15th: X and Y, not Z" — never a silent re-labeling of the required answer as the expected one.

## 5. Deliver a range with its levers

```
Likely N–M ⟨units⟩ · assumes: ⟨the 2–4 assumptions that, broken, break the number⟩
· biggest risk: ⟨the unknown, and the spike that would shrink it⟩
· cut line: ⟨what drops to hit the low end⟩
```

Width is information — a 2–6 week range on a fuzzy project is *more accurate* than "4 weeks", and it tells the requester exactly how much definition would buy precision. Resist the compression to one number; if forced, give the number *with* its confidence ("70% by the 20th") so slippage is calibrated, not a betrayal. When new information moves the estimate, say so at the moment it moves — a re-estimate at 30% done is a course correction; the same news at 95% is a broken promise.

## Anti-patterns

- Anchoring on the number in the question ("this is quick, right?") — decompose first, answer second.
- Estimating under social pressure in the meeting; "I'll have a range in an hour" is a professional answer.
- Padding everything silently instead of stating uncertainty — hidden padding destroys the calibration data and gets negotiated away anyway.
- Precision theater: "13.5 hours" for work with ±3-day unknowns. Match significant figures to actual knowledge.
- Treating the estimate as the commitment ceremony — an estimate is a forecast; renegotiate it when reality diverges, don't quietly crunch to protect it.
- Never comparing estimates to actuals — the loop that would make next quarter's numbers better than this quarter's.
