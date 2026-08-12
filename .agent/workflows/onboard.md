---
description: Learn an unfamiliar codebase fast — orient from artifacts, trace one real flow, predict-then-check, write the map
---

# /onboard

Argument: the repo/service/module and the goal (fix a bug, add a feature, own it).

## Steps

1. **Fix the goal** — it sets depth: a bug → one flow; a feature → module X's contracts and neighbors; ownership → architecture + operations. Write it down; let it kill tangents.
2. **Orient, breadth-first**: README/docs/ADRs (as claims to verify — docs rot) · build/run/test entry points (Makefile, scripts, CI — the honest self-description; **get the tests running now** for a green baseline) · top two directory levels + dependency manifest · git (`git log --oneline -30`; most-touched files are the load-bearing walls; recent PRs show conventions). Output: an architecture sketch labeled UNVERIFIED.
3. **Trace one real flow end-to-end**: the most representative operation, through every layer — entry → dispatch → logic → persistence → response. Read the actual code per hop; add logging/debugger where reading gets speculative (DI, dynamic dispatch, middleware). Bug goal → the failing flow is the slice (switch to /debug); feature goal → trace the closest sibling; it's the template.
4. **Interrogate the model**: question ledger ("why is this here?" written, not chased; batch survivors) · predict-then-check (say what a file will contain before opening; wrong prediction = fix the sketch) · Chesterton's fence: the weird thing is load-bearing until `git log -p` says otherwise.
5. **Leave a trail**: the verified sketch, the traced flow, goal-relevant seams, surprises, open Q&A — where the repo keeps notes. First change: small, fully checkable, through the full process (branch, review, CI) — the process is part of the codebase.
6. Never: trusting names over evidence ("utils/ is trivial") · redesign opinions before one traced flow · asking humans what artifacts answer · "reading around" a broken build.
