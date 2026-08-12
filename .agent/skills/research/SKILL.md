---
name: research
description: Triangulated research with a citation log — primary sources first, two independent sources for load-bearing claims, disconfirmation hunting, synthesis that separates fact from inference. Use for any research task — technical evaluations, "which X should we use", market/landscape questions, fact-finding, due diligence — and before answering factual questions where being wrong is costly.
---

# Research

Research output is only as strong as its weakest load-bearing claim. The recurring failures: single-source facts, stale information presented as current, vendor marketing absorbed as truth, and synthesis that launders uncertainty into confidence. The protocol attacks each one.

## 1. Sharpen the question

State what decision this research informs and what answer would change it — "evaluating Postgres vs. DynamoDB *for this access pattern*" researches differently than "compare databases". Decompose into the 3–6 sub-questions that would settle it. If no decision hangs on a sub-question, drop it.

## 2. Rank sources before reading

Primary — the spec, the docs, the source code, the paper, the filing, the changelog — beats secondary analysis beats aggregated content (blogs, forums, AI summaries). Go as primary as the question allows; every hop from the primary source adds someone else's interpretation.

**Date-check everything.** Note publication date; for fast-moving topics, anything older than a release cycle is a historical document. Record the access date — pages change.

## 3. Triangulate load-bearing claims

Any claim the conclusion rests on needs **two independent sources** — independent meaning not citing each other and not restating the same press release (twenty articles paraphrasing one announcement is one source). What can't be triangulated gets labeled: *"single source: X"*. Vendor claims about their own product are marketing until independently confirmed — benchmarks especially.

## 4. Log citations as you go

Keep a running block in a scratch file:

```
CLAIM: <the fact as you'll use it>
  src: <title/URL> (<pub date>, accessed <date>) — <primary|secondary>
  confidence: confirmed (2+ independent) | likely (1 solid) | uncertain
```

Re-finding a lost source costs 10× logging it. The log is also the audit trail that lets a reader — or a cheaper model later — check your work instead of re-doing it.

## 5. Hunt disconfirmation

Before concluding, actively search against yourself: "X problems", "X limitations", "X vs Y", "migrating away from X", the strongest critic's case. A conclusion you haven't tried to break is a draft. Finding the counter-case and weighing it *is* the analysis; omitting it is advocacy.

## 6. Know when to stop

Stop when new sources stop changing the answer — novelty saturation — or when the remaining unknowns no longer affect the decision. Then say what was **not** investigated and what would change the conclusion ("didn't evaluate self-hosted pricing; a 10× traffic estimate revision would flip this"). Bounded honesty beats implied completeness.

## 7. Synthesize

- **Answer first** — the conclusion in the first two sentences, then support. Never a chronology of your searching.
- Inline attribution for every load-bearing claim; the citation log as appendix when the deliverable warrants it.
- Confidence labels on key claims: confirmed / likely / uncertain. Readers act differently on each — hiding the difference transfers your risk to them.
- Keep the three registers visibly separate: **fact** (sourced), **inference** (yours, from the facts — say so), **opinion/recommendation** (labeled, with the criterion it optimizes).

## Rules

- When the answer is checkable, check it — never present recalled knowledge as verified fact. If you assert from memory, label it: "from memory, unverified".
- Numbers carry units, dates, and context ("40% faster" — than what, measured how, by whom?).
- Quotes are verbatim with a pointer; paraphrases are labeled as paraphrase.
- Contradictions between good sources are a finding, not an inconvenience — report both sides and why they might differ (methodology, date, incentive).
