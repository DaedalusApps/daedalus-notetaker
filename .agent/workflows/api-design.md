---
description: Design interfaces from the caller's side — sample calls first, explicit contract with errors, evolution planned before v1
---

# /api-design

Argument: the API/library surface/CLI/event schema to design or review.

## Steps

1. **Calls first**: write 5–10 realistic call sites verbatim as the consumer would — including pagination loops, retry-after-failure, partial updates, error handling. Awkward call = wrong design; fix now. Then derive the interface that makes those calls true. Name the primary consumer and design for them.
2. **Pin the contract** per operation: inputs (types, units, ranges, on-invalid) · outputs on success AND partial success · a branchable error taxonomy (machine code + human detail; caller-can-fix / retry / can't-help) · semantics (idempotency for every mutation, timeouts, pagination stability, concurrent updates) · empty-vs-absent-vs-null decided once.
3. **Consistency beats cleverness**: one naming convention, casing, pagination scheme, error shape, timestamp format (ISO 8601 UTC), ID type. Follow platform conventions (HTTP verbs/status straight; stdlib idioms). Novelty is a defect unless it pays.
4. **Plan evolution before v1**: smallest surface serving the step-1 calls (add later; never remove — in doubt, leave out) · additive changes require consumers to ignore unknowns — state it · pick versioning mechanism and deprecation promise now.
5. **Review as a one-way door**: replay each sample call against the final design · write the easiest misuse and make illegal states unrepresentable where possible · every error path has a defined shape · extending an existing API: its conventions win over your preferences. Hard-to-reverse pieces get /architect treatment.
6. Deliver: contract table · runnable sample calls (kept tested) · versioning + deprecation policy · explicit non-goals. HTTP → OpenAPI beats prose. Never: schema-outward design, mode-parameter god endpoints, booleans that will grow a third state, errors as parseable strings or 200-with-error-body.
