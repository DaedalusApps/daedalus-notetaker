---
description: Design with honest tradeoffs — steelmanned options, one-way-door analysis, decision recorded as an ADR
---

# /architect

Argument: the design question or system to design. Produce a decision, not code.

## Steps

1. Write the problem before any option: the need in behavior terms; the real constraints (read the actual codebase — stack, patterns, scale hints; label estimates as estimates); which requirements are *likely* to change. Design flexes for likely changes only.
2. Generate 2–3 real options plus the baseline "do nothing / minimal change". Steelman each — state the best genuine case for the option you like least. An option included only to lose rigs the comparison.
3. Compare on the dimensions that decide THIS case: complexity added and who carries it, blast radius, migration path from today, operational burden, reversibility. Label the decision **one-way door** (schema, public API, data model — full analysis, maybe spike first) or **two-way door** (decide fast, stay cheap to reverse).
4. Decide. Default to boring technology unless a named requirement demands otherwise. Record as an ADR: **Context** (problem, constraints, options considered) / **Decision** (precisely what) / **Consequences** (what gets easier, what gets harder — every real decision has accepted downsides; without them it's marketing — and what would make us revisit).
5. Add verification and retreat: how we'll know it works (acceptance checks, observability), rollout order, rollback path. "No rollback — destructive" is legitimate but must be written down.
6. List the top 3 risks, each with the cheapest probe that would surface it early. The riskiest assumption's probe becomes implementation task #1 (hand to `/breakdown`).
