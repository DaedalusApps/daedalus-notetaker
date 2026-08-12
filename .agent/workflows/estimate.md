---
description: Calibrated estimates — decompose, size by reference, spike the unknowns, deliver a range with assumptions
---

# /estimate

Argument: the work to size (and the deadline, if one exists).

## Steps

1. **Estimate the right thing**: pin "done" first — fuzzy scope gets per-interpretation numbers ("CSV only: X; full: Y"; the gap forces the scope decision). Include the whole cost: review, tests, docs, deploy, migration, coordination.
2. **Decompose and mark**: parts small enough to resemble work actually done before; size by reference ("last three similar endpoints: ~2 days each"), never intuition about a novel whole. Mark each KNOWN / VARIABLE / UNKNOWN — the unknowns are where estimates die.
3. **Attack unknowns**: a timeboxed spike (read the docs, prototype the join, measure the volume) often collapses an unknown to a known for an hour. Still unknown → estimate the discovery ("2 days to determine feasibility, then re-estimate"), never a number-shaped shrug. "Can't usefully estimate until after the spike" is a legitimate answer.
4. **Correct for bias**: the gut total is the range's bottom (planning fallacy is the default) · apply your own actuals ratio (what did the last "2-day" tasks take?) · integration and iteration are line items · a deadline is not an estimate — fixed date converts to scope: "by the 15th: X and Y, not Z".
5. **Deliver**: `Likely N–M · assumes: <2–4 breakers> · biggest risk: <the unknown + its spike> · cut line: <what drops for the low end>`. Width is information. Forced to one number → attach confidence ("70% by the 20th"). Re-estimate aloud the moment new information moves it.
6. Never: anchoring on the number in the question · estimating under social pressure ("range in an hour" is professional) · silent padding · precision theater ("13.5 hours" with ±3-day unknowns) · skipping the estimates-vs-actuals comparison afterward.
