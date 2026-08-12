---
name: architect
description: Design with honest tradeoffs — problem and constraints first, 2–3 steelmanned options plus a do-nothing baseline, one-way vs two-way door analysis, decision recorded as an ADR with its accepted downsides. Use when designing a system or feature, choosing between technologies or approaches, writing a design doc or ADR, or making any decision that is expensive to reverse.
---

# Architect

Architecture is choosing which future changes will be cheap. The recurring failures: designing for imagined requirements, comparing options dishonestly (your favorite's best case vs. the alternatives' worst), and not recording *why* — so the decision gets relitigated every quarter or silently reversed by someone who never saw the reasoning.

## 1. Problem before solutions

Write down, before any option is named:

- **The need** — what must become possible, in behavior terms.
- **Constraints** — real load and latency numbers (measured or honestly estimated, labeled which), team size and skills, deadline, the existing stack, operational capacity. Constraints are the design inputs; a design that ignores one is fiction.
- **Likely change** — which requirements will plausibly change soon (scale? providers? schema?). Design flexes for the *likely* changes; flexing for hypothetical ones is how accidental complexity gets justified.

## 2. Generate honest options

Two or three real candidates, **plus the baseline: do nothing / minimal change**. The baseline forces the actual question — is this worth building at all? — and calibrates every option's cost.

**Steelman each option**, especially the one you dislike: state the best genuine case for it. An option included only to lose is a rigged comparison, and rigged comparisons produce decisions that don't survive contact with a skeptic.

## 3. Compare on the dimensions that decide *this* case

Not generic pros/cons — the loaded dimensions here, usually among: complexity added (and who carries it), blast radius when it breaks, migration path from today, operational burden (who gets paged), cost, and **reversibility**.

Label the decision as a **one-way door** (schema in production, public API, data model) or **two-way door** (internal structure, swappable dependency). Two-way doors deserve fast decisions and cheap experiments; one-way doors deserve the full analysis and maybe a spike first. Spending equal scrutiny on both is misallocation in both directions.

## 4. Decide, and record it as an ADR

```markdown
# ADR-<n>: <decision, one line>
Date · Status: accepted

## Context
<The problem, the constraints that mattered, the options considered — 3–6 sentences.>

## Decision
<What we're doing, precisely.>

## Consequences
<What becomes easier. What becomes harder — every real decision has accepted
downsides; an ADR without them is marketing. What would make us revisit.>
```

The Consequences section is the whole point: it's what stops the decision from being relitigated without new evidence, and what tells a future reader whether the revisit-condition has arrived.

## 5. Default to boring

Novelty is a cost paid in unknown failure modes, hiring, and operational surprise. Spend your innovation budget on the product's actual differentiator; everywhere else, choose the technology whose failure modes are already documented on the internet. Deviate only when a *named* requirement demands it — "the team wants to try X" is a real consideration, but label it as the motivation rather than dressing it in fabricated technical necessity.

## 6. Design for verification and retreat

A design isn't done until it says how you'll know it works and how you'll back out: acceptance checks (`spec` criteria at the system level), the observability that will show it working or failing in production, rollout order, and the rollback path. "Rollback: none, this migrates data destructively" is legitimate — but it must be written down, because it reclassifies the decision as a one-way door.

## 7. Attack the risks

Top three risks, each with the **cheapest probe that would surface it early** — a spike of the riskiest interface, a load test of the scary query, a one-day prototype of the unproven integration. Probe order becomes task order (`breakdown` starts from the riskiest assumption). Risks without probes are worries; risks with probes are a plan.

## Anti-patterns

- Resume-driven choices dressed as requirements.
- Designing the system you wish you had instead of the delta from the one you have — migration is part of the design, usually the hard part.
- Abstraction before the second concrete use case (the rule of three: first time write it, second time copy it, third time abstract it).
- The "design" that is just the implementation restated — no alternatives considered means no decision was made.
- Deciding by committee-averaging two options into a hybrid with both options' costs.
