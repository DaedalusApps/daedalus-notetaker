---
name: postmortem
description: Blameless postmortem that converts an incident into systemic fixes — factual timeline, contributing causes (plural, not one root cause), action items that would each have prevented or shortened the incident. Use after an incident, outage, data loss, near-miss, or any "how did that happen" retrospective.
---

# Postmortem

A postmortem buys learning with the incident's cost — the incident is a sunk price; the only variable is how much you get for it. Two failure modes waste it: the blame hunt (produces defensiveness and hidden information, fixes nothing) and the ritual document (produces "be more careful" and action items nobody does). The test of a good postmortem is falsifiable: *would its action items, had they existed before, have prevented the incident or materially shortened it?*

## 1. Reconstruct the timeline — facts only

From the incident log, alerts, deploy history, and chat scrollback: a timestamped sequence of *observable events* — what happened, what people saw, what they did. No causes yet, no "mistakenly", no "should have" — narrative judgments this early contaminate the analysis. Include the boring anchors: when did impact start (often well before detection — measure that gap), when detected, when mitigated, when resolved. The gaps between those timestamps are findings in themselves: a 4-hour detection gap is usually a bigger lever than the bug.

## 2. Blameless is a method, not a courtesy

Write people as interchangeable roles ("the on-call", "the deploying engineer"). The reasoning: the person acted on the information and incentives the *system* gave them, and the next person will get the same information and incentives — so fixing the person fixes nothing. Every time the analysis lands on a human error, ask what made that error easy: the misleading dashboard, the two-similarly-named configs, the alert that fires so often it's ignored, the runbook that didn't exist. **A conclusion of "human error" is a signal the analysis stopped early.** Same for "be more careful" — an unfundable action item.

## 3. Causes, plural

"Root cause" (singular) is the wrong shape: incidents happen when several defenses fail *together* — the bug existed AND review missed it AND tests didn't cover it AND the canary didn't catch it AND the alert didn't fire. Walk the chain with "why / and why didn't anything stop it there?" at each link. Each failed defense is a candidate fix, and defenses are usually cheaper to fix than the original bug class is to eliminate. Distinguish **trigger** (the deploy) from **conditions** (the latent bug, the missing limit) — removing only the trigger leaves the conditions armed for a different trigger.

Also collect what went *well* (fast rollback, good log discipline) — those are defenses to keep funded, and they make blamelessness credible.

## 4. Action items that actually bind

Each one passes four tests: **specific** (a change to a system, not to vigilance) · **owned** (a name, not a team) · **dated** · **connected** (which timeline gap or failed defense does it fix — prevention, faster detection, faster mitigation, or smaller blast radius?). Rank by leverage: the fix that catches the whole bug *class* beats the fix for this bug; the detection fix that cuts every future incident's length can beat both. Three funded items beat twelve aspirational ones — a long list is where accountability goes to die. And schedule the check: a postmortem whose action items silently expire teaches the org that postmortems are theater.

## 5. The document

```
Summary (3 sentences: what broke, impact, duration) · Impact (users, data,
duration, in user terms) · Timeline (timestamped facts; detection/mitigation
gaps called out) · Causes (trigger, conditions, each failed defense) ·
What went well · Action items (owner, date, which gap each closes) ·
Appendix (log excerpts, graphs)
```

Write for the reader two years out who hits something similar: searchable title, exact error messages quoted, links that will survive. Publish where the whole team reads it — a postmortem in a drawer prevented nothing.

## Anti-patterns

- Scapegoat with extra steps: a "blameless" document where everyone can compute the name.
- The counterfactual pile-on ("X should have noticed") — hindsight makes everything obvious; analyze what the information *available at the time* supported.
- Action item "add more tests" / "improve monitoring" — which test, for which class, watching which signal?
- Severity inflation or deflation to fit a narrative — the impact section is measurements.
- Skipping postmortems for near-misses. A near-miss is a free postmortem — same learning, none of the damage.
- Litigating the timeline in the meeting. Circulate the draft first; the meeting is for causes and action items.
