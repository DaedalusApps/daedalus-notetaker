---
description: Production incident response — mitigate before diagnosing, smallest reversible action, timestamped log, cadence comms
---

# /incident

Argument: the incident (symptom, alert, or "prod is down").

## Steps

1. **Assess (2 min)**: impact (who, what, since when — check the user-facing symptom yourself) · severity (all/some/internal; data corruption outranks downtime) · trajectory. State it in the channel.
2. **Log**: timestamped, append-only, from minute one — observations, actions, effects. Log every state-changing action before taking it.
3. **Mitigate** — "what returns service fastest at lowest risk", not "what's the bug": what changed? (deploys, config, flags, providers) → rollback of the suspect change is the default first move; correlation suffices. No candidate → shed and shield: flag off, fail over, scale up, rate-limit, serve degraded. Reversible beats clever; snapshot before touching data. ONE mitigation at a time, verified against the user-facing symptom; worse → revert immediately.
4. **Communicate on a cadence**: impact in user terms, status, next update time — kept even with nothing new. No external cause speculation, no guessed ETAs. Multiple responders → coordinator (comms) vs hands-on-keyboard named.
5. **Stand down deliberately**: recovered = symptom gone through one full cycle (peak, cron). Declare the end · ticket the follow-ups NOW (disabled flag, skipped root cause, snapshots) · preserve evidence before it rotates · schedule /postmortem · then root-cause calmly via /debug.
6. Rules: mitigation is not the fix — the incident ends twice (users; root cause fixed + mitigation unwound). Irreversible actions get a second ack even mid-incident. Blame hides the information you need. Two failed mitigations and guessing → escalate.
