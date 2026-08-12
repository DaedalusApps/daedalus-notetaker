---
description: Analysis that survives scrutiny — interrogate the data before computing, plot before summarizing, exploration ≠ confirmation
---

# /data-analysis

Argument: the question and the dataset(s).

## Steps

1. **Sharpen the question**: what exactly, which population, which window, and what decision does the answer change? Write down before looking: expected result and what would surprise you.
2. **Interrogate the data** (before any computation): provenance (who produced it, how sampled — the generating process bounds what it can say) · per-column meaning (units, timezone, what null means, semantics changes mid-history) · quality sweep (row counts, duplicate keys, impossible values, null rates per period, and **row counts before/after every join** — fan-out/drop-out is the silent corruption) · missingness structure (random or correlated with the outcome?). "We measured X's proxy, not X" is often the report's most important line.
3. **Look before summarizing**: plot distributions (skew, bimodality, mass at zero); eyeball raw rows from both tails; pick summaries fitting the shape (median/percentiles for skew); segment the headline by time/cohort/platform — aggregates reverse across segments.
4. **Keep modes separate**: patterns found by exploration are hypotheses — confirm on held-out data, the next period, or an experiment; never on the data that produced them. Confirmation mode: metric, population, threshold defined before running; run once; no re-slicing to significance. Causal claims from observational data → "associated" + confounders + what would establish causation.
5. **Report at evidence strength**: effect size with uncertainty in decision units (never bare "significant"; give n) · chain of custody (source, filters, definitions, n per stage) · caveats in the same breath · "what would change this conclusion" · precision matching knowledge. Notebook runs top-to-bottom clean; queries versioned; randomness seeded.
6. Never: computing on unexamined data · dropping "weird" points without a stated rule · metric shopping · burying the finding that the question was wrong.
