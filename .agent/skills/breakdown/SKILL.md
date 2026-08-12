---
name: breakdown
description: Decompose work into independently verifiable tasks with Definitions of Done, dependencies, and explicit interfaces — sized for one session, ordered by risk. Use when planning a multi-step feature or project, splitting work for parallel agents or sessions, or when a task is too big to hold in one context window.
---

# Breakdown

Decomposition quality decides whether executors — subagents, future sessions, cheaper models, teammates — can work independently or will be back every ten minutes with questions. The unit of good decomposition: a task its executor can complete *and prove complete* without asking anything.

## 1. Slice by verifiability, not by architecture layer

Each task ends in an observable check. Vertical slices ("the endpoint returns X for input Y, end to end") beat horizontal layers ("write all the models", "write all the handlers") — a completed vertical slice is *proven* working and delivers value if everything after it is cancelled; a completed layer is inventory whose defects surface only at integration, the most expensive moment.

## 2. Write task cards

```markdown
### T3: <outcome, not activity — "retries are idempotent", not "work on retries">
Goal: <one sentence>
Touches: <files/areas — the collision-detection field for parallel work>
DoD: <command + expected observable result — the executor's finish line>
Depends on: <T1, T2 or —>
Notes: <decisions already made, gotchas known, file:line pointers —
       a mini-handoff; this field is what makes the card executable cold>
```

The DoD is the card's load-bearing field, same bar as a `spec` acceptance criterion: a stranger could run it and get a yes or no. "Code written" is not a DoD; "`curl /health` returns 200 with version string" is. Investigation tasks get a question as their DoD: "answered: which auth flow does the vendor SDK actually use, with evidence."

## 3. Size for one session

One focused session, one reviewable commit per task. Heuristics: a DoD that needs "and" three times → split. A task that is pure ceremony alone → merge it into its consumer. When in doubt, smaller — task-switching costs less than a task that outgrows its context window and ends in a degraded-quality finish.

## 4. Order by risk, then dependency

The task probing the **riskiest assumption** goes first (from `spec`/`architect`) — if the plan is going to be invalidated, buy that information at the cheapest possible moment. Then topological order by dependency, noting which tasks can run in parallel.

**Parallel tasks must not edit the same files.** The `Touches` fields are the collision check — overlap means serialize them or re-slice.

## 5. Write the contracts between tasks

When T4 consumes what T2 produces, write the interface — signature, schema, format, endpoint shape — into *both* cards at breakdown time. That contract is what lets them proceed in parallel or across sessions without drift; without it, integration becomes the place where two reasonable interpretations meet and fight. Include an integration task whenever two streams must merge — unassigned integration is how "all tasks done" and "nothing works" coexist.

## 6. Track with evidence

Statuses: `todo / in progress / done / blocked-on-<what>`. **Done requires the DoD evidence** — the command output, not the executor's assertion (this is `prove` applied per-task). The tracker is the project's handoff surface: kept current, any session can pick up any unblocked task cold.

## 7. Replan on contact

When executing a task reveals the plan is wrong — the assumption failed, the interface won't work, T5 is unnecessary — update the remaining cards then, and note what changed and why. Pushing through a stale plan turns every subsequent task into rework. The plan is a tool, not a promise.

## Ceremony scales with risk

A 3-step task gets 3 bullet points with DoDs, inline, thirty seconds. A multi-session project gets full cards in a `TASKS.md`. A 15-card plan for a 3-task problem is procrastination wearing a safety vest.

## Anti-patterns

- Tasks named by activity ("investigate caching", "work on API") with no observable finish line.
- Hidden coupling — two "independent" tasks that both edit the same config, discovered at merge time.
- The 90%-done plan: every task "almost finished", nothing proven — a symptom of missing DoDs.
- Saving the riskiest task for last, when discovering its failure costs the most.
- Decomposing past the point of usefulness — sub-splitting work that one session would finish faster than the cards take to write.
