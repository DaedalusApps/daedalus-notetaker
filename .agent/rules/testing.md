---
trigger: model_decision
description: Apply when writing or improving tests, adding regression tests after a fix, or asked to add test coverage.
---

# Test design protocol

A test's value is the probability it fails when the code is wrong. Coverage measures execution, not assertion strength.

- Derive cases from the **contract**, not the implementation: for each promise — normal case, boundary cases (empty/single/many/huge; zero/negative/max; exactly-at-limit and one-past), and violation cases (invalid input → the promised failure behavior). The unit's behavior when wronged is its least-tested, most bug-dense promise.
- Sweep the applicable boundaries: unicode and injection-shaped strings · missing/malformed/duplicate · time (timezone, DST, leap, epoch) · concurrency (same operation twice in flight) · dependency failure (collaborator throws/times out/returns partial).
- One behavior per test; the name states the rule (`rejects_expired_token`, not `test_2`). Assert observable behavior — return values, emitted events, persisted state — never internals or call sequences. Deterministic: inject clocks, seed randomness, fake the network at the boundary. Independent: any subset runs in any order.
- **Prove each load-bearing test can fail**: break the code deliberately (revert the fix, mutate the condition), watch it fail for the stated reason, restore. Mandatory for regression tests — fail on pre-fix code or it isn't one.
- Oracles: exact expected values > property checks (round-trips, idempotence, invariants) > snapshots (last resort, must be read before committing). Never compute the expected value with the implementation's own logic.
- Spend tests where bugs are expensive: boundaries, error paths, money/time/auth/concurrency, anything that just had a bug. Skip framework behavior, trivial getters, and mock-talks-to-mock tests. Mock only at system boundaries; every mock encodes an assumption — know where it's verified.
- No `sleep()` for asynchrony — await a condition.
