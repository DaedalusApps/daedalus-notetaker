---
name: api-design
description: Design interfaces from the consumer's side — write the calls you wish existed, make the contract explicit including errors, plan evolution before v1 ships. Use when designing or reviewing an HTTP/RPC API, a library's public surface, a CLI, an event schema, or any boundary other people's code will depend on.
---

# API Design

An API is a promise you can't easily unmake: once someone depends on it, every quirk is load-bearing (Hyrum's law) and every change is a negotiation. So the design effort goes where the leverage is — before first release — and the design method starts from the *caller's* code, because an API is judged from outside.

## 1. Start from the calls, not the schema

Write the consuming code first: 5–10 realistic call sites, verbatim, as the consumer would write them — including the annoying cases (pagination loop, retry after failure, partial update). This is `spec` applied to an interface: the sample calls are the acceptance criteria. If the call site is awkward to write, the design is wrong *now*, while it's cheap. Then, and only then, derive the interface that makes those calls true.

Identify the actual consumers and rank them: an API serving three audiences equally usually serves none well. Design for the primary consumer; accommodate the rest.

## 2. Make the contract explicit

The signature is the smallest part of the contract. For each operation, pin down and write down:

- **Inputs**: types, units, ranges, required/optional, what validates and what happens on invalid.
- **Outputs**: the shape on success — and on *partial* success (some items failed: error? partial result with markers?).
- **Errors**: a designed error model, not an accreted one — a small stable taxonomy the caller can *branch on* (machine-readable code), with human detail alongside. Distinguish caller-can-fix (validation), caller-should-retry (transient), and caller-can't-help (bug/outage).
- **Semantics**: idempotency (retries are a fact of networks — mutating operations need idempotency keys or natural idempotency), timeouts, ordering guarantees, pagination stability, concurrency (what happens on simultaneous update).
- **Nullability and emptiness**: empty list vs. absent field vs. null — pick meanings once, apply everywhere.

## 3. Consistency beats cleverness

Every inconsistency is a permanent tax on every consumer's memory. Within the API: one naming convention, one casing, one pagination scheme, one error shape, one timestamp format (ISO 8601 UTC, or your ecosystem's one standard), one ID type. With the ecosystem: follow the platform's conventions (HTTP verbs/status codes used straight; your language's stdlib idioms for libraries) — familiarity is a feature you get for free. Novelty in an API needs to pay for itself; "interesting" is a defect here.

## 4. Design the evolution before v1

- **Smallest surface that serves the calls from step 1.** You can add later; you can never remove. Every method, field, and option you didn't ship is a future you thanking you. When in doubt, leave it out.
- **Know your additive moves**: adding an optional field/param is safe iff consumers ignore unknowns — state that expectation in the contract. Changing types, semantics, or defaults is never additive.
- **Versioning**: decide the mechanism now (URL/header for HTTP, semver discipline for libraries) even if v2 never comes. Decide what you *promise* about deprecation: how long, what warning, what migration doc.
- **Deprecation is part of the API**: mark it in the type system / OpenAPI / docs, point at the replacement, and actually remove on the stated schedule — a deprecation nobody enforces is just clutter.

## 5. Review it as a one-way door

Before shipping: walk each sample call from step 1 against the final design (they still read well?) · try to write the *misuse* — what's the easiest way for a consumer to hold this wrong, and can the design make that impossible (make illegal states unrepresentable) rather than documented? · check the contract table for holes (every error path has a defined shape?) · run it past `architect`'s one-way-door test — anything hard to reverse gets an ADR. For an existing API being extended: the existing conventions win over your preferences, even the ugly ones — consistency with itself beats local improvement (fixing the convention is a `migrate`-scale decision, not a rider on a feature).

## Deliverable

The contract, written down where consumers will find it: the operations table (inputs/outputs/errors/semantics per step 2), the sample calls as runnable examples (examples are the most-read documentation — keep them tested), the versioning/deprecation policy, and the explicit non-goals ("this API does not support X" prevents the accidental promise). For HTTP: an OpenAPI/schema file beats prose. For libraries: the examples double as doctests.

## Anti-patterns

- Designing from the database schema outward (exposes your storage decisions as promises).
- The god endpoint/function with a `mode` parameter — split it.
- Booleans that will grow a third state — use enums from day one.
- Errors as strings the caller must parse, or worse, as `200 OK` with an error body.
- Breaking change disguised as a bugfix ("nobody relies on that" — someone does).
- Optional behavior toggles multiplying: every flag doubles the test matrix and someone depends on each combination.
