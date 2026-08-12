---
description: Write or update HANDOFF.md so any session, model, or tool can resume this work cold
---

# /handoff

Write for a reader with zero conversation history, possibly a weaker model. Pointers over pasted content; state over history.

## Steps

1. Write/update `HANDOFF.md` at the repo root, replacing stale content (current state only — never an appended diary):
   ```markdown
   # Handoff: <task, one line>
   Updated: <date> · State: <in progress | blocked | ready for review>

   ## Goal
   <one sentence: what done looks like, for whom/why>

   ## Ledger
   1. [DONE] <requirement> — evidence: <command + decisive output line>
   2. [IN PROGRESS] <requirement> — exact state
   3. [TODO] … · [BLOCKED] … — on: <precisely what>

   ## Next action
   <executable without thinking: "Run X. Expect Y. If Z, cause is likely W — check path:line.">

   ## Decisions
   - <decision> — because <reason>

   ## Gotchas
   - <what looked right but wasn't; flaky checks; invariants; refuted approaches and why they fail>

   ## Map
   - <file:line> — <why it matters> · Build/Test/Run: <verbatim commands>

   ## Verification state
   I verified: <…>. I did not verify: <…>.
   ```
2. Commands, paths, and identifiers verbatim from tool output — never retyped from memory.
3. Self-test before saving: could a stranger reach full working speed from this file alone, without this conversation? If not, add what's missing (usually a Gotcha or a Decision).
4. Resuming instead? Read the whole brief, spot-check its two cheapest load-bearing claims (run the test claimed green; confirm pointers exist), then execute Next action and keep the ledger current.
