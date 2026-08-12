---
name: write
description: Audience → thesis → outline → draft → exactly one structured revision pass. Use for any prose deliverable — docs, READMEs, design docs, proposals, reports, announcements, emails, tutorials — whenever the writing itself is the product rather than a byproduct.
---

# Write

Writing quality is decided before drafting (audience, thesis, structure) and after (one disciplined revision) — almost never by polishing sentences mid-draft. And revision without a rubric is churn: open-ended "make it better" loops swap words, add filler, and sometimes break what was working.

## 1. Define the assignment — one sentence each

- **Audience**: what they already know, what they need from this, what they'll skim vs. read.
- **Purpose**: what the reader should *do or believe* after reading. Not "inform about X" — "choose option B" / "be able to run the tool".
- **Form**: genre, tone, and a **length budget** picked now, as a real constraint. Most prose improves when forced to fit 70% of its natural sprawl.

## 2. Thesis

Write the one sentence the piece exists to convey. Everything in the piece either supports it or leaves. If you can't write this sentence, the problem is thinking, not writing — go resolve it (often via `research` or `spec`) before drafting.

## 3. Outline

Sections with one-line jobs. Check the order tells the reader's story, not yours: **answer first, then support** — never the chronology of your own thinking. A section without a job gets cut here, where cutting is free.

## 4. Draft straight through

No polishing mid-draft — momentum beats elegance in draft state; polish belongs to the revision pass where you can see the whole. Stuck on the opening? Write it last; openings are easy once the body exists. Mark unverified facts `[CHECK]` as you go rather than breaking flow.

## 5. Revise — exactly one structured pass

Against this rubric, in order:

1. **Opening states the point.** The reader knows the takeaway by sentence two. Delete throat-clearing ("In today's fast-paced world…", "This document describes…").
2. **Structure audit.** Every section still earns its job; every paragraph does one thing; nothing said twice.
3. **Concreteness.** Load-bearing claims have an example, a number, or a name. Abstract nouns doing heavy lifting get grounded or cut.
4. **Audience audit.** Every term of jargon: does *this* audience know it? Every assumption of context: do they have it?
5. **Truth pass.** Resolve every `[CHECK]`; every checkable fact checked or explicitly hedged. Adjectives making claims evidence should make ("blazing fast") get evidence or deletion.
6. **Cut 10–15%.** Filler adverbs, hedges stacked two deep, restated points, the paragraph that exists because writing it felt thorough. Hit the length budget.

Then ship. Revise again only on external feedback or an objective failure — not vibes.

## Genre notes

- **README**: what it is, why you'd use it, and a working quickstart — all in the first screen.
- **Design doc / proposal**: decision and tradeoffs up front; details for the skeptical reader behind them. (Structure via `architect`.)
- **Email / message**: the ask in the first two lines; context after, for those who need it.
- **Tutorial**: the reader runs something every few paragraphs; each step's output shown so they know they're on track.
- **Commit / PR message**: what changed and why — the code already says how.
- **Status update**: outcome first, then blockers with asks attached, then detail.

## Anti-patterns

- Drafting without a thesis and hoping structure emerges.
- Symmetric coverage of asymmetric material — giving "both sides" equal space when the evidence is 90/10.
- Hedging every sentence until the piece asserts nothing. Hedge the genuinely uncertain claims, precisely, once each.
- The essay-shaped answer to a yes/no question.
- Polishing sentences in a piece with a structural problem — rearranging furniture in the wrong house.
