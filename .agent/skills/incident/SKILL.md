---
name: incident
description: Production incident response — stabilize before diagnosing, mitigate with the smallest reversible action, keep a timestamped log, communicate on a cadence. Use when production is down or degraded, users are impacted, an alert is firing, or "something is wrong in prod" — before any root-cause work.
---

# Incident

Incident response inverts normal engineering: **mitigation before diagnosis**. Users don't experience root causes, they experience downtime — and the fastest path back is almost never "find the bug", it's "undo what changed" or "route around it". Understanding can wait; the full `debug` treatment happens after service is restored, and the learning happens in `postmortem`. During the incident there are only three questions, in order: *how bad, how do we stop the bleeding, what do we tell people.*

## 1. Assess — two minutes, not twenty

- **Impact**: who is affected, what can't they do, since when? Check the user-facing symptom yourself — dashboards lie in both directions.
- **Severity**: all users vs. some vs. internal-only; data being corrupted vs. requests failing (data corruption outranks downtime — a system down writes no bad data).
- **Trajectory**: getting worse, stable, or recovering on its own?

Say the assessment out loud/in the channel — it's the anchor for every later decision, and it starts the log.

## 2. Start the log

Timestamped, append-only, from the first minute: observations, actions taken, by whom, effects seen. During the incident it prevents the classic disasters (two people making conflicting changes; re-trying what already failed); after, it *is* the postmortem's raw material. Every state-changing action gets logged **before** it's taken.

## 3. Mitigate — smallest reversible action first

The question is not "what's the bug" but "what change returns service fastest at lowest risk":

- **What changed?** Deploys, config pushes, feature-flag flips, dependency/provider incidents, traffic shifts — most incidents follow a change, and **rollback of the suspect change is the default first move**. Roll back on correlation; you don't need proven causation to un-deploy.
- No candidate change → **shed and shield**: flag off the failing feature, fail over, scale up, rate-limit the aggressor, serve degraded (cached/read-only) rather than nothing.
- Prefer reversible over clever: restart-the-pod beats hand-editing state; if you must touch data, snapshot first.
- **One mitigation at a time**, verified against the user-facing symptom (not just the internal metric) before the next. Simultaneous mitigations make recovery unattributable — you won't know what fixed it or what to un-do later.

If a mitigation makes things worse, revert it immediately — the log tells you what "back" means.

## 4. Communicate on a cadence

Stakeholders get: impact in user terms, current status, next update time. Then **keep the cadence even when there's nothing new** — "still investigating, next update 15:30" preserves trust; silence spends it. Don't speculate about causes externally; don't promise ETAs you're guessing at. If more than one responder: name who's coordinating vs. who's hands-on-keyboard — the coordinator communicates, so investigation never stalls for an update.

## 5. Stand down deliberately

Recovered means the *user-facing* symptom is gone and stayed gone through one full cycle of whatever periodicity mattered (traffic peak, cron run). Then: declare the end in the channel · file the follow-ups **now** while they're vivid (the disabled flag, the skipped root-cause, the snapshot to clean up — each gets a ticket, not a memory) · schedule the `postmortem` · and only then do the root-cause hunt via `debug`, calmly, on the preserved evidence (grab logs/metrics/core dumps before they rotate).

## Rules

- Mitigation is not the fix. A rolled-back deploy still contains the bug; a flag off is a feature down. The incident ends twice — once for users, once when the root cause is fixed and the mitigation is unwound.
- Don't debug in prod what you can debug from prod's evidence. Poking the live system is itself a change and can extend the incident.
- Wrong-but-fast beats right-but-slow *only for reversible actions*. Irreversible actions (data deletion, failover you can't fail back, restarting the only replica holding state) get a second person's ack, even mid-incident.
- Blame has zero operational value and suppresses the information flow you need right now. "The deploy correlates" is a fact; whose deploy is irrelevant until the retro — and mostly then too.
- If you're guessing wildly after two mitigation attempts, escalate — fresh eyes and system owners beat heroics. Asking for help early is a skill, not a failure.
