---
name: data-analysis
description: Analysis that survives scrutiny — interrogate the data's provenance and quality before computing, distinguish exploration from confirmation, quantify uncertainty, report what would change the conclusion. Use when exploring a dataset, answering a question with data, building metrics/dashboards, evaluating an experiment, or checking someone else's numbers.
---

# Data Analysis

The output of analysis is a decision someone will make — and the most dangerous artifact in that pipeline is a precise-looking number computed from data nobody interrogated. Most analysis failures are not statistical; they're upstream: the column didn't mean what its name said, the nulls weren't random, the join silently dropped a third of the rows. So this protocol spends its effort where the failures live — provenance first, mechanics second, honesty about uncertainty throughout.

## 1. Sharpen the question before touching data

"Look into churn" is not analyzable. Convert to a decision-shaped question: *what specifically, over what population, over what window, and — the calibrating one — what decision will the answer change?* If no decision depends on it, the right analysis is often none. Write down, before looking: what result you expect, and what result would surprise you. This is the cheap insurance against HARKing — deciding what the question was after seeing which answer looks interesting.

## 2. Interrogate the data before computing on it

Every dataset is guilty until proven innocent:

- **Provenance** — who/what produced it, when, how sampled? The generating process defines what the data *can* say: support tickets can't measure satisfaction, only ticket-filing.
- **Meaning** — for each load-bearing column: units, timezone, what null means (never observed? zero? deleted?), whether the semantics changed mid-history (the silent killer of trend analyses — look for discontinuities at deploy dates).
- **Quality sweep** — row counts vs. expectation; duplicates on the supposed key; ranges and impossible values (negative ages, future timestamps); null rates *per column per time period*; for joins, row counts before and after — **every join is a hypothesis about keys**, and fan-out or drop-out is the most common silent corruption.
- **Missingness structure** — is what's missing random, or correlated with the outcome? (Churned users going quiet before churning means the gap *is* the signal.)

Findings here go in the final report; "we measured X's proxy, not X" is often the most important sentence in it.

## 3. Look at the data before summarizing it

Plot distributions before trusting any aggregate: means without distributions are how bimodal data becomes a fictional "typical user", and one outlier becomes a trend. Check the shape (skew, multimodality, mass at zero), eyeball raw rows from both tails (tails are where data bugs live), and only then choose summaries that fit the shape (medians/percentiles for skew). Segment the headline number by the obvious cuts (time, cohort, platform) — aggregates routinely reverse across segments (Simpson's paradox), and the segmented view is usually the actionable one.

## 4. Exploration and confirmation are different modes

Exploring — cutting the data many ways, hunting patterns — is legitimate and generative, but a pattern *found* by exploration cannot be *confirmed* by the same data: test twenty cuts and one is "significant" by luck. Label exploratory findings as hypotheses; confirm on held-out data, the next time period, or a designed experiment. In confirmation mode: define the metric, population, and success threshold *before* running; run once; resist the re-slice-until-significant loop (garden of forking paths). And for anything causal — "X drives Y" — observational data plus a regression is a correlation wearing a suit; say "associated", name the plausible confounders, and state what would establish causation (experiment, natural experiment, at minimum a mechanism).

## 5. Report the finding at the strength the evidence supports

- Effect size with uncertainty, in decision-relevant units — "reduces churn 1.2±0.8pp/month", never a bare "significant" (statistical significance ≠ practical importance, and n=30 vs n=3M changes everything).
- **The chain of custody**: data source, filters applied, definition of each metric, n at each stage. An analysis whose numbers can't be reproduced from its own description is an anecdote.
- Caveats in the same breath as the finding, not a slide later: the proxy gap, the excluded population, the assumption that would break it.
- **What would change this conclusion** — the analysis's falsifiability clause, and the sentence that most distinguishes analysis from advocacy.
- Match precision to knowledge: "roughly 40%" when the error bars are ±10pp; "38.4%" is precision theater.

Code hygiene rides along: the analysis is a program (`prove` applies) — seed randomness, version the queries, and make the notebook run top-to-bottom clean before anyone sees its numbers.

## Anti-patterns

- Computing on data you haven't looked at.
- The unexamined join; the unexamined null.
- Metric shopping ("which definition of retention makes the launch look good?") and its twin, dropping "weird" data points without a stated rule.
- Extrapolating beyond the data's support — the model fit on power users, deployed on everyone.
- Dashboard metrics nobody can define when asked ("what exactly counts as active?").
- Answering the question asked while burying the finding that matters more — if the data revealed the question was wrong, that's the headline.
