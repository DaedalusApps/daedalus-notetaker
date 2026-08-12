---
name: brainstorm
description: Structured ideation that escapes the first idea's gravity — generate wide without judging, force variation along named axes, then converge with explicit criteria and kill reasons. Use when asked to brainstorm, generate options/names/approaches, find alternatives to a stuck plan, or when a decision is being made with only one candidate on the table.
---

# Brainstorm

The failure mode of ideation isn't producing too few ideas — it's producing one idea five times. The first plausible idea creates a gravity well: everything after is a variation on it, and evaluation quietly starts before generation ends, killing the weird candidates that were the session's whole point. So the protocol enforces the one rule that matters — **generate and judge in separate phases** — and replaces "think harder" with mechanical moves that force genuine variation. A brainstorm that ends with a pile of ideas is also unfinished: the deliverable is a *decision-ready shortlist*, not confetti.

## 1. Frame the problem, not the solution

State the actual need one level above the requested artifact: "names for the caching library" sits under "how do we make this adoptable and memorable". Note the real constraints (hard ones only — budget, compatibility, law) and explicitly *suspend* the soft ones ("we've always done X") for the duration; half of them turn out to be habits, and marking which is which is itself a finding. If a favored solution already exists, write it down and set it aside visibly — otherwise it becomes the hidden benchmark every idea gets judged against.

## 2. Generate wide — quantity is the mechanism

Volume is not decoration: the first ideas are everyone's ideas (available, obvious, already considered); originality lives in the tail you only reach by pushing past the point of comfort. Target 15–30+ for most questions. Rules of the phase: no evaluation, no feasibility talk, no "but" — infeasible ideas are raw material (the bad idea's *underlying move* often transplants into a good one). When output stalls, don't push harder on the same angle — switch angles:

- **Invert** — how would we guarantee failure? (Each guaranteed-failure, reversed, is a candidate.) What if we did nothing?
- **Extremes** — the 10x-budget version; the $0 version; the ship-tomorrow version; the version for 1 user; for 1M users.
- **Perspectives** — how would a security engineer solve this? A game designer? A librarian? How does nature / a market / a bureaucracy solve the analogous problem?
- **Decompose & recombine** — split the problem into parts, generate per part, cross-breed the partial answers.
- **Analogy** — who has a structurally identical problem in a different domain, and what's their standard solution?
- **Remove the sacred part** — take the component everyone assumes is fixed and design without it.

Number every idea and keep phrasing terse — a line each. Long descriptions this early are evaluation in disguise.

## 3. Converge with named criteria

Now judgment — but explicit, so it can be argued with:

1. **Cluster** the pile into families; the clusters reveal the actual axes of choice (and a cluster with one member is often the interesting one).
2. **State the criteria** before scoring — impact, cost, reversibility, fit — and weight them. Criteria chosen after seeing favorites is the convergence version of HARKing.
3. **Shortlist 2–4** genuinely *different* candidates — the best member of each strong family, not the top four of one family (that's the first-idea gravity well wearing a ranking).
4. **Kill with reasons**: notable discards get one line on why. The kill reasons document the search space for whoever second-guesses the shortlist later — and "killed by soft constraint" entries are flags to revisit if constraints loosen.
5. Salvage before discarding: the strongest ideas are frequently hybrids ("A's mechanism with B's interface").

## 4. Hand off to a decision

The output: the shortlist with a one-line case each, the criteria used, the kill list, and **the cheapest next test per candidate** — the prototype, the mock, the one-day spike that would falsify it (`estimate`'s spike logic). For decisions with real stakes, the shortlist feeds `architect` (tradeoffs, one-way doors); a brainstorm may *not* silently become a commitment — picking the winner is a separate, explicit act.

## Anti-patterns

- Evaluating during generation — one "that won't work because…" reshapes everything generated after it.
- Anchoring the session with the boss's / requester's idea first; strong-opinion inputs go in *last*.
- Fifteen paraphrases counted as fifteen ideas — check variation by asking whether the *mechanisms* differ, not the words.
- Converging by enthusiasm ("I like 7!") instead of criteria.
- The infinite diverge — refusing to converge is as sterile as refusing to diverge; timebox both phases.
- Deleting the discards. The kill list is half the value; next quarter's brainstorm starts from it.
