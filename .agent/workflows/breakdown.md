---
description: Decompose work into independently verifiable task cards with Definitions of Done, dependencies, and explicit interfaces
---

# /breakdown

Argument: the feature, plan, or spec to decompose.

## Steps

1. Slice by **verifiability, not architecture layer**: each task ends in an observable check. Vertical slices ("endpoint returns X for Y, end to end") beat horizontal layers ("write all the models") — a finished slice is proven working; a finished layer is unproven inventory.
2. Write one card per task:
   ```
   ### T<n>: <outcome, not activity>
   Goal: <one sentence>
   Touches: <files/areas — the collision-detection field>
   DoD: <command + expected observable result>
   Depends on: <T…, or —>
   Notes: <decisions made, gotchas, file:line pointers — enough to execute cold>
   ```
   "Code written" is not a DoD; "`curl /health` returns 200 with version string" is. Investigation tasks get a question as DoD: "answered, with evidence: …".
3. Size for one focused session / one reviewable commit. A DoD needing "and" three times → split. A pure-ceremony task → merge into its consumer.
4. Order by risk, then dependency: the riskiest-assumption probe goes first — buy the plan-invalidating information at the cheapest moment. Mark what can run in parallel; parallel tasks must not overlap in `Touches` — overlap means serialize or re-slice.
5. Where task B consumes task A's output, write the interface (signature/schema/format) into **both** cards now. Add an integration task wherever two streams merge — unassigned integration is how "all tasks done" and "nothing works" coexist.
6. Write the cards to `TASKS.md` with statuses (`todo / in progress / done / blocked-on-<what>`). "Done" requires the DoD evidence, not an assertion. When execution reveals the plan is wrong, update the remaining cards then — note what changed and why.
