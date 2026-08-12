---
description: Load the owner's standard working process — roles, board-as-plan, red-first TDD, three gates, decision logging — and follow it for this session
---

# /apply-working-process

Work the way the library's owner works. The visible process is the deliverable.

## Steps

1. **Roles:** plan → implement → review as separate phases. Delegate implementation to cheaper tiers where subagents exist; otherwise phase-separate yourself. Every diff gets a cold review (`/deep-review`) before it lands. The orchestrator itself runs on Fable-tier by default; if the user lacks Fable access, offer to switch the orchestrator to Opus at high reasoning effort instead of silently downgrading.
2. **Board-as-plan:** tasks are issues (`P<phase>.<seq>`) carrying scope, a Red-first line, Done-when criteria, and a DoD checklist. Work discovered mid-task gets an issue *first*, then a branch. No board → `WORKPLAN.md`, same structure.
3. **One issue = one branch = one PR.** Nothing to main without a PR; every PR `Closes #N`. Branches `feat/p<N>-<slug>`; conventional commits + `Assisted-by:` trailer.
4. **Red first:** commit the failing artifact (test / eval case / browser scenario) visibly failing before implementing. Pair mock-based tests with real-stack scenarios. Model evals record locally, replay in CI.
5. **Three gates on the full PR diff, in order:** `/declutter` → `/sec-audit` → `/deep-review`. Fix every finding, re-run the suite green after fixes. Docs PRs included. Fix-first beats ship-with-follow-up.
6. **Log decisions same-session** in local gitignored `prd/DECISIONS.md` (`Decision / Alternatives rejected / Why`). Surface your own judgment calls to the owner. Never publish strategy notes or unverified findings.
7. **Norms:** do-now bias; never game a metric; in unattended runs self-merge only after gates and leave owner-gated items open+annotated. Check in on long-running subagents every 15 minutes — prefer a free spot-check (git status, docker ps, GPU/resource stats, artifact dirs) before spending a message on asking the agent; confirm real progress from their actual output, not just that they're still running; interrupt and redirect anything stalled. CI watches are bounded — a hung run is a failure mode, not a long run: derive the ceiling from the pipeline's known runtime (a suite that finishes in ~30 seconds gets a ~5-minute ceiling, not "until it ends"); never watch unbounded. On breach, diagnose once: a run still queued never started — cancel and re-run it once; a run genuinely executing gets one extension with a stated reason. Two strikes → surface to the owner instead of looping. Right after opening a PR, poll until checks exist (short loop, ~2-minute cap) before reading their absence as a verdict — "no checks reported" immediately post-push is usually the registration race, not the result. Brief subagents with the ceiling/re-run rule, not an open-ended `--watch`; and the 15-minute spot-check of a delegated task includes "is it parked on a CI watch past the ceiling?" (`gh run list` is free). (Observed 2026-07-24: an agent read "no checks reported" pre-registration; an unbounded watch would have slept through a queued-runner stall.) Brief every subagent to never end its turn to "wait" on a background/detached/slow process — no wake-up notification ever arrives, so it would sleep forever; poll inline in a bounded loop and continue in the same turn instead.
8. **New environment:** state once how each element maps to available tooling, then follow the mapping — don't silently drop awkward parts.
