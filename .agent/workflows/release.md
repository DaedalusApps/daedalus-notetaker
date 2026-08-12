---
description: Ship so failure is cheap — verify the exact artifact, stage exposure, watch against baseline, rollback rehearsed
---

# /release

Argument: what's shipping (defaults to the current branch's release candidate).

## Steps

1. **Before**: read the exact diff since last release as a risk list (migrations, auth, config parsing, serialization → extra care) · everything green on the exact artifact (same commit, same build) · rollback written down: the command, its duration, and whether this change breaks rollback (schema/one-way transforms → expand-migrate-contract via /migrate) · timing: responders present; not Friday evening, not the peak, not beside another launch.
2. **Stage the exposure** — never 0→100: dev → staging → canary → ramp → full. Each rung gets a pre-defined verdict ("error rate and p95 flat vs baseline for 30 min"), not a pause — an uncompared canary is just a slow deploy. Feature flags decouple deploy from release: ship dark, flip separately; flag-off is the fastest rollback; ticket flag removal at full ramp.
3. **After** — deploy exit 0 starts the experiment: watch user-facing signals against pre-release baseline + the changed component's logs (new-in-this-release error strings are the top alert) · watch delayed shapes (memory creep, first cron, cache expiry, first peak) and size the watch window accordingly · exercise the shipped feature once end-to-end in prod.
4. **On failure**: roll back first, diagnose second (/incident). Roll forward only when the fix is minutes away and understood. A rolled-back release re-ships from the top of the ladder.
5. **The checklist** lives in the repo, versioned, followed by reading it; every incident adds a line. Tag what shipped; changelog for the operator diffing versions mid-incident.
6. Never: batching a week of merges because releases hurt (pain → smaller and more frequent) · "quick config change" outside the ladder · promoting a canary on "no pages yet" · declaring victory at deploy-success.
