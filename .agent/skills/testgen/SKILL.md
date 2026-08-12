---
name: testgen
description: Write tests that hunt bugs, not coverage theater — cases derived from the contract and its boundaries, each test proven able to fail, assertions on observable behavior. Use when writing or improving tests, adding regression tests after a fix, or when asked to "add test coverage" for existing code.
---

# Testgen

A test's value is the probability it fails when the code is wrong. Coverage measures what executed, not what was checked — a suite can execute 95% of lines and assert almost nothing. Write tests as an adversary of the implementation: each one is a trap set for a specific class of bug.

## 1. Derive cases from the contract, not the code

Read (or state) what the unit *promises*. From each promise derive three groups:

- **Normal**: representative valid input → promised output.
- **Boundary**: the edges of valid — empty/single/many/huge; zero/negative/max; exactly-at-limit and one-past-limit.
- **Violation**: invalid input and hostile conditions → the promised *failure* behavior (which error, what state afterward). What a unit does when wronged is usually its least-tested, most-bug-dense promise.

Deriving from the implementation instead ("line 12 has an if, so test both branches") reproduces the author's blind spots — the bug is precisely in the case they didn't think of.

## 2. Sweep the standard boundary table

For each input, check which of these apply and pick the applicable ones — don't write all of them ritually:

empty / single / many / huge · zero / negative / float-precision / integer-max · unicode, whitespace-only, injection-shaped strings (`'; DROP`, `../..`, `<script>`) · missing / malformed / duplicated · time: timezone, DST transition, leap day, epoch boundaries, clock skew · concurrency: same operation twice in flight, interleaved writers · dependency failure: what does the unit do when its collaborator throws, times out, returns partial data?

## 3. Make each test a good instrument

- **One behavior per test**; the name states the rule: `rejects_expired_token`, not `test_token_2`. A failing name should tell you what broke without reading the test.
- **Assert observable behavior** — return values, emitted events, persisted state — not internals or call sequences. Behavior-asserting tests survive refactors; implementation-asserting tests punish them.
- **Deterministic**: control time (inject a clock), randomness (seed), and network (fake at the boundary). A flaky test is worse than no test — it trains people to ignore red.
- **Independent**: any subset runs in any order. Shared mutable fixtures are the usual culprit.

## 4. Prove the test can fail

A test you have never seen red is unverified — it may be asserting nothing. For every load-bearing test: break the code deliberately (revert the fix, mutate the condition), watch the test fail *for the stated reason*, restore. For a regression test after a bugfix this is mandatory: fail on the pre-fix code or it isn't a regression test.

## 5. Choose honest oracles

Exact expected values > property checks > snapshots. Properties shine where the contract *is* a property: encode/decode round-trips, idempotence (`f(f(x)) == f(x)`), invariants (balance never negative), commutativity. Snapshots are a last resort for genuinely visual output — and a snapshot nobody read before committing asserts nothing. Never compute the expected value with the same logic as the implementation — that's a tautology that passes forever.

## 6. Spend tests where bugs are expensive

Prioritize: boundaries and error paths · money, time, auth, and concurrency logic · anything that just had a bug (every bug gets a regression test — bugs cluster) · public contracts other teams depend on. Skip: framework behavior, trivial getters/pass-throughs, and mock-talks-to-mock tests that verify the mock.

## Mock discipline

Mock at the system boundary — network, clock, filesystem, external services — not in the middle of your own logic. Over-mocked tests freeze the implementation and pass while production burns. Every mock encodes an assumption about the real dependency; know where that assumption is verified (a contract test, an integration test) or flag that it isn't.

## Anti-patterns

- "It runs without throwing" as the sole assertion.
- One giant test walking the happy path end to end — when it fails, it says nothing; boundary bugs sail through.
- Expected values copy-pasted from the code's current output without checking they're *correct* (pinning a bug ≠ knowing about it — that's fine for `refactor` characterization tests, but label them).
- `sleep()` for asynchrony — poll or await a condition.
- Writing tests to satisfy a coverage number. Write to kill hypothetical mutants; coverage follows.

Proving a fix? Pair this with `prove`; the regression test is step one of its evidence.
