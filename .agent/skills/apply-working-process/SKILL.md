---
name: apply-working-process
description: Load the owner's standard working process — orchestrator-only role split, cheap-model implementation with fresh equal-or-better review, board-as-plan issue discipline, red-first TDD, three gates before merge, same-session decision logging. Use at the start of any project session, in any environment or tool, so the agent works the way the owner works.
---

# Apply Working Process

This is how the library's owner runs engineering work. It was proven on the AgentForge series (a multi-phase portfolio build) and applies to any project unless they say otherwise. The principle underneath all of it: **the visible process is itself the evidence of quality** — red→green history, review gates, and an honest board are the deliverable, not overhead.

## 1. Roles and model routing

- **The orchestrator plans, sequences, delegates, reviews, and merges — it never edits files directly.**
- Implementation runs on cheaper models: Sonnet-tier for all real work, Haiku-tier only for pure boilerplate (scaffolding, fixtures, config). Token savings are an explicit owner directive, not an optimization to debate.
- **Every diff is reviewed by a fresh agent on an equal-or-better model** before it lands — fresh means no implementation context, so it can't inherit the implementer's blind spots.
- The top-tier model is reserved for rare, genuinely high-stakes security review — and its use is called out explicitly.
- No subagents in this environment? Keep the *phase separation*: plan first, implement second, then review your own diff cold with `deep-review` before committing.
- The orchestrator itself runs on the strongest model available for the seat — Fable-tier by default. If the user doesn't have access to Fable, offer to switch the orchestrator to Opus at high reasoning effort instead of silently downgrading.
- The full model-selection judgment lives in this library's `playbooks/ROUTING.md`; this section is its standing application, and ROUTING.md wins if they drift.

## 2. The plan lives on a board, publicly

- A public GitHub Project (or the environment's equivalent) is the **live operational plan**: phases → milestones, tasks → issues titled `P<phase>.<seq>`, labels for phase and implementation tier.
- Each issue body carries: scope, a **Red-first** line (the failing artifact to write before implementing), **Done when** (acceptance criteria), and the Definition-of-Done checklist.
- Planning documents freeze once imported to the board; plan changes happen in issues. (Exception: the test plan stays a living document.)
- **The board never lags reality.** Work discovered mid-task gets an issue created *first* — with acceptance criteria — then a branch. No board access? Keep a `WORKPLAN.md` with the same structure.

## 3. One issue = one branch = one PR

- Nothing reaches `main` without a feature branch and a PR. No direct pushes, no "quick fixes."
- Every PR links its issue via `Closes #N`; a PR with no linked issue does not merge.
- Branches: `feat/p<N>-<slug>` (or `fix/`, `docs/`, `ci/`). Conventional commits, with an `Assisted-by: <tool>` trailer when an AI helped.
- Once the branch exists, `pr-workflow` owns the craft: commit narrative, PR description, review dialogue.

## 4. Red first — strict TDD, everywhere

- The failing artifact is committed and **visibly failing in PR history** before implementation: unit test for logic, eval case for agent behavior, browser scenario for UI flows.
- Every mock-based test of an external system is paired with a scenario against the real running stack — mocks mirror assumptions; scenarios check them.
- Model-inference evals never run in CI: live runs happen locally and record outputs; CI replays recordings through deterministic assertions.
- This is a conscious owner override of hybrid approaches — the ~20–30% schedule cost is accepted for uniform "no untested line" discipline. Don't relitigate it.

## 5. Three gates before anything merges

- On the full PR diff, in order: **simplify → security review → code review** (in Claude Code: `/simplify` → `/security-review` → `/code-review`; elsewhere use this library's `declutter`, `sec-audit`, `deep-review`).
- Every finding fixed — not triaged into follow-ups — and the full test suite re-run green *after* the fixes. Applies to docs PRs too.
- When a gate finds a real defect, **fix-first beats ship-with-follow-up**, even if the reviewer says shipping is acceptable.

## 6. Decisions are logged the session they're made

- A local, gitignored `prd/` directory holds the full plan and `DECISIONS.md`. Every owner decision gets an entry **in the same session**: `Decision / Alternatives rejected / Why`.
- Judgment calls the agent makes autonomously are logged there *and* surfaced to the owner for review (as a board issue, or explicitly on return).
- `DECISIONS.md` is the running owner log; one-way-door architecture decisions *additionally* get an ADR (see `architect`) — the log entry points at the ADR, not the other way around.
- Three visibility tiers, never mixed: (1) committed/public docs and code — stripped of strategy and meta-framing; (2) the public board — the live plan; (3) local `prd/` — strategy, decision log, unverified findings. Unverified vulnerability claims are never published.

## 7. Working norms

- **Anything that can be done now gets done now** — environment setup runs immediately, outside tracked tasks, so problems surface before the first task's pipeline.
- **Honest measurement.** Never game a metric: a non-deterministic test stays xfail rather than being flaked green; eval numbers report what actually happened.
- **Autonomy with accountability.** When granted an unattended run: self-merge after the gates pass, and leave owner-gated items open and annotated rather than blocking on them.
- **Check in on long-running subagents every 15 minutes.** A delegated task still "running" isn't proof it's progressing — read back its actual output or status and confirm real progress, not just that the process is alive. Prefer a free, independent spot-check of the environment (`git status`, `docker ps`, GPU/resource stats, artifact directories) before spending a message or resume on asking the agent itself. Stalled or looping work gets interrupted and redirected, not left to burn budget silently.
- **CI watches are bounded — a hung run is a failure mode, not a long run.** Derive the ceiling from the pipeline's known runtime (a suite that finishes in ~30 seconds gets a ~5-minute ceiling, not "until it ends"); never watch unbounded. On breach, diagnose once: a run still queued never started — cancel and re-run it once; a run genuinely executing gets one extension with a stated reason. Two strikes → surface to the owner instead of looping. Right after opening a PR, poll until checks *exist* (short loop, ~2-minute cap) before reading their absence as a verdict — "no checks reported" immediately post-push is usually the registration race, not the result. Brief subagents with the ceiling/re-run rule, not an open-ended `--watch`; and the 15-minute spot-check of a delegated task includes "is it parked on a CI watch past the ceiling?" (`gh run list` is free). (Observed 2026-07-24: an agent read "no checks reported" pre-registration; an unbounded watch would have slept through a queued-runner stall.)
- **Brief subagents to never passively wait.** A subagent must never end its turn to "wait" for a background, detached, or slow process — detached work produces no wake-up notification, so an agent that stops to wait for one sleeps forever. Instead it polls inline in a bounded foreground loop (check → sleep → re-check) and continues to the next step in the same turn; long-running work is launched foreground or harness-tracked, never detached-and-then-stopped. (Observed twice in one task on 2026-07-21: a subagent stopped to await a notification that could never arrive.)

## Adapting to a new environment

On first use in a tool or repo, map each element to what exists: which review skills stand in for the three gates, whether a board or `WORKPLAN.md` carries the plan, whether subagents or phase separation carry the role split. State the mapping once, then follow it — don't silently drop the elements the environment makes awkward.
