---
name: handoff
description: Write a compact continuation brief so a fresh session — or a cheaper model — resumes exactly where this one left off, without re-deriving anything. Use when context is running long, when ending a session mid-task, when downshifting work to a smaller model, or when delegating a subtask. Also defines how to RESUME from a handoff.
---

# Handoff

Context is the most expensive thing an agent builds and the easiest thing to lose. A handoff transfers the *state of mind* — goal, decisions, gotchas, exact next action — not the transcript. Write it for a reader with zero conversation history and possibly less capability than you: everything they need, nothing they can look up themselves.

## When to write one

- Context is running long and quality will degrade before the task ends.
- Session is ending with the task incomplete.
- Work is downshifting to a cheaper/smaller model (the fable-quality-library ROUTING playbook governs when) — the handoff is what makes the downshift safe.
- A subtask is being delegated to a subagent or parallel session.

## Where

One file, updated in place — `HANDOFF.md` at the repo root or `.claude/HANDOFF.md` (project-local, gitignore it if it would add noise). Never append a diary; the successor needs current state, not history.

## The brief — seven blocks, ≤ one page

```markdown
# Handoff: <task, one line>
Updated: <date> · State: <in progress | blocked | ready for review>

## Goal
<One sentence: what done looks like, and for whom/why — prevents scope drift.>

## Ledger
1. [DONE] <requirement> — evidence: <command + decisive output line>
2. [IN PROGRESS] <requirement> — exact state: <what's built, what's not>
3. [TODO] <requirement>
4. [BLOCKED] <requirement> — on: <precisely what unblocks it>

## Next action
<The single next step, executable without thinking:
"Run X. Expect Y. If Z instead, the cause is likely W — check path:line.">

## Decisions
- <decision> — because <reason>. (Stops the successor from relitigating or undoing it.)

## Gotchas
- <what looked right but wasn't; flaky checks; invariants that must hold;
  refuted approaches a successor would plausibly retry, and why they fail>

## Map
- <file:line> — <what lives there / why it matters to this task>
- Build: `<verbatim command>` · Test: `<verbatim command>` · Run: `<verbatim command>`

## Verification state
I verified: <claims with evidence>. I did not verify: <claims + why>.
```

## Quality bar

The successor reaches full working speed **without re-reading the conversation and without re-deriving anything you already learned**. The self-test: could *you* resume cold from only this file in a month? Density beats completeness — pointers (`file:line`) over pasted content, decisions over narration.

## Rules

- Commands, paths, and identifiers **verbatim from tool output** — retyped-from-memory identifiers are the #1 handoff corruption.
- No journey narrative. What order things happened in is irrelevant; only current state matters. The exception: refuted approaches that a successor would plausibly retry — list those under Gotchas with why they fail.
- Write it *before* quality degrades, not after — a handoff written by an exhausted context inherits its confusion.
- Update in place on every significant state change if the file is the ongoing coordination point.

## Resuming from a handoff

1. Read the whole brief before acting.
2. **Spot-check the two or three cheapest load-bearing claims** — run the test that is claimed green, confirm the file:line pointers exist. Trust, but verify the foundation before building on it; a stale handoff silently poisons everything after it.
3. Execute the Next action. Keep the ledger statuses current as you work; you are now the maintainer of the brief.
4. Do not relitigate recorded decisions without new evidence — if you disagree, say so explicitly rather than quietly rebuilding.
