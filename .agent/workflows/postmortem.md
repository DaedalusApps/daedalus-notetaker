---
description: Blameless postmortem — factual timeline, plural causes, action items that would each have prevented or shortened it
---

# /postmortem

Argument: the incident to analyze (link the incident log/channel).

## Steps

1. **Timeline, facts only**: timestamped observable events from the log/alerts/deploys/chat — no causes, no "mistakenly" yet. Include impact-start (often before detection — measure the gap), detection, mitigation, resolution; the gaps are findings (a 4-hour detection gap usually outranks the bug).
2. **Blameless as method**: people as roles ("the on-call"); they acted on what the system gave them and the next person gets the same. Landing on "human error" = analysis stopped early — ask what made the error easy (misleading dashboard, twin configs, alert fatigue, missing runbook). "Be more careful" is not an action item.
3. **Causes, plural**: several defenses failed together — bug existed AND review missed AND tests didn't cover AND canary didn't catch AND alert didn't fire. Walk each link with "why didn't anything stop it there?"; each failed defense is a candidate fix, usually cheaper than eliminating the bug class. Separate trigger (the deploy) from conditions (the latent bug). List what went well — defenses to keep funded.
4. **Action items that bind**: specific (changes a system, not vigilance) · owned (a name) · dated · connected to the gap it closes (prevention / detection / mitigation speed / blast radius). Rank by leverage: bug-class fix > this-bug fix; detection fixes shorten all future incidents. Three funded beat twelve aspirational; schedule the follow-up check.
5. **Document**: Summary (3 sentences) · Impact (user terms, measured) · Timeline (gaps called out) · Causes · What went well · Action items (owner/date/gap) · Appendix. Quote exact error messages for the future searcher; circulate the draft before the meeting; publish where the team reads.
6. Never: counterfactual pile-ons ("X should have noticed" — analyze what the available information supported), "add more tests" without which test for which class, skipped near-miss postmortems (same learning, zero damage).
