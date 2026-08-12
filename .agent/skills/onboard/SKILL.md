---
name: onboard
description: Build an accurate working model of an unfamiliar codebase fast — orient from the artifacts, trace one real flow end-to-end, verify beliefs against runtime behavior, write down the map you built. Use when joining a project, picking up an unfamiliar repo/service/module, or before making changes in code you've never touched.
---

# Onboard

The danger in an unfamiliar codebase isn't ignorance — it's the plausible-but-wrong model built from naming and vibes ("this is *obviously* where requests come in") that silently shapes every later change. Onboarding is hypothesis-testing against a system that already works: the goal is a model you've *verified*, deep where you'll work and deliberately shallow everywhere else. Full coverage is neither possible nor the point.

## 1. Fix the goal first

"Understand the codebase" is unbounded. Real goals are shaped like: *fix this bug* (understand one flow), *add feature to module X* (X's contracts and neighbors), *own this service* (architecture + operations + failure modes). The goal decides depth; write it down and let it kill tangents — the codebase will offer you infinite interesting corridors.

## 2. Orient from the artifacts — one hour, breadth-first

In rough order of information-per-minute:

- **README / docs / ADRs** — read skeptically; docs describe intentions and rot silently. Treat every claim as a hypothesis to verify.
- **The build/run/test entry points** (Makefile, package.json scripts, CI config) — what the project *actually does* is encoded here more honestly than in prose. **Get the tests running now**: it verifies your environment, and a green baseline is the prerequisite for safely touching anything.
- **Directory shape + dependency manifest** — the top two levels and the imports tell you the architecture's intent and the tech stack's reality.
- **Git history** — `git log --oneline -30` for what's active; the most-touched files (`git log --format= --name-only | sort | uniq -c | sort -rn | head -20`) are the load-bearing walls; recent PRs show conventions, review culture, and where the bodies are buried.

Output of this hour: a guessed architecture sketch — components and arrows — explicitly labeled *unverified*.

## 3. Trace one real flow end-to-end

Pick the most representative operation (a request, a CLI command, a pipeline run) and follow it through the layers: entry point → routing/dispatch → business logic → persistence/external calls → response. Read the actual code at each hop; run it with a debugger or logging where reading gets speculative (dependency injection, dynamic dispatch, and middleware chains are where reading alone fails). One verified vertical slice teaches more than any breadth pass: it forces every layer, exposes the *real* conventions, and calibrates how much the docs lie. If your goal is a bug, the failing flow is the slice (`debug` takes over); if a feature, trace its closest existing sibling — it's also your implementation template.

## 4. Interrogate the model

- Keep a **question ledger** — every "why is this here?" gets written down, not chased immediately. Half will answer themselves within the slice; batch the rest for a human or a `research-codebase` pass. (The ones with no discoverable answer are often bugs or dead code — flag, don't assume.)
- **Predict-then-check**: before opening a file, say what you expect it to contain; before running a flow, predict the log lines. Wrong predictions are the model-fixing events — a surprise means update the sketch, not shrug.
- Respect Chesterton's fence: the weird thing is load-bearing until history (`git log -p` on it, the PR that added it) says otherwise. First-week "cleanup" instincts are usually wrong about which walls are decorative.

## 5. Leave a trail

Write the map you built: the verified architecture sketch, the traced flow, where the goal-relevant seams are, the surprises ("X is named Y for historical reasons", "tests need Z running"), and the open questions with their answers. The act of writing catches the parts you only think you understand — and the next person (possibly a cheaper model resuming via `handoff`) starts from your map instead of from zero. If the repo has a docs/notes convention, put it there; improving the README's setup section with what actually made the tests pass is the traditional first contribution.

## The verification habit

First changes prove the model: start with something small whose blast radius you can fully check (the bug fix, a test for existing behavior — writing a test for code you *think* you understand is the cheapest possible model check). Ship it through the project's full process (branch conventions, review, CI) — the process is part of the codebase. Confidence to make large changes is earned in verified increments, not reading hours.

## Anti-patterns

- Reading linearly, file by file — comprehension without a goal doesn't stick and doesn't finish.
- Trusting names ("`utils/` is trivial", "`legacy/` is unused") over evidence.
- Redesign opinions before tracing a single flow.
- Asking humans what the artifacts already answer (spends goodwill), or grinding hours on what a maintainer answers in one sentence (spends time) — batch real questions, ask with your ledger's evidence attached.
- Skipping environment setup and "reading around" a broken build — you're onboarding onto a system you can't observe.
