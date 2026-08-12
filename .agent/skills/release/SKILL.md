---
name: release
description: Ship changes so that failure is cheap — verify before, stage the exposure, watch the right signals after, keep rollback rehearsed and instant. Use when deploying, releasing, publishing a package, running a launch, flipping a major feature flag, or writing a release/deploy checklist.
---

# Release

A release is a bet that the new version behaves in production the way it behaved everywhere else. Good release engineering doesn't make the bet safe — it makes it **cheap to lose**: small increments, watched closely, reversible instantly. Every practice below is one of those three levers. The inverse — big-bang, unwatched, irreversible — is how minor bugs become incidents.

## 1. Before: earn the deploy

- **Know what you're shipping**: the exact diff since the last released version, read as a list of risks, not a list of features. Anything touching migration, auth, config parsing, or serialization raises the care level.
- **Green everything**, on the exact artifact being shipped (same commit, same build) — "it passed on my branch" plus a merge is a different artifact.
- **Rollback answer written down** before shipping: the command, how long it takes, and — the question that catches people — *does this change break rollback?* Schema migrations, one-way data transforms, and protocol bumps can make the previous version unable to run; those need expand-migrate-contract (`migrate`) so old and new coexist.
- **Timing is a choice**: ship when the people who can respond are around and watching, not at 6pm Friday, not during the traffic peak, not alongside another team's launch. Boring timing is a feature.

## 2. Stage the exposure

Never 0→100. The exposure ladder, as far as your infrastructure supports: dev → staging → canary (one instance / few % of traffic) → progressive ramp → full. Two rules make the ladder real:

- **Each rung needs a verdict, not a pause.** Define *before shipping* what signal, watched for how long, promotes to the next rung ("error rate and p95 flat on canary for 30 min"). A canary nobody compares against baseline is just a slow deploy.
- **Feature flags decouple deploy from release.** Shipping dark (code deployed, flag off) makes deploy risk and feature risk separately debuggable — and flag-off is the fastest rollback that exists. The discipline cost: flags are `migrate`-style long-tail debt; ticket the removal when fully ramped.

## 3. After: watch, don't assume

The release isn't done when the deploy exits 0 — that's when the experiment starts.

- Watch the **user-facing signals** (error rate, latency, key business metric) against pre-release baseline, plus the logs of the changed component. New-in-this-release error messages are the highest-signal alert there is.
- Watch for the delayed failure shapes: memory creep (shows in hours), the first cron/batch run, the cache expiring, the first traffic peak. Define how long "watched" lasts based on which of those apply.
- **Verify the feature itself in prod** — one real end-to-end exercise of the shipped thing. Deploys that "succeeded" while the feature silently no-ops are common and embarrassing.

## 4. When it goes wrong

Roll back first, diagnose second (`incident` owns the protocol) — with staged exposure the blast radius is a canary, which is the whole point. Two specifics: **roll back, don't roll forward** unless the forward fix is truly minutes away and understood — hotfixes written under pressure have the highest defect rate of any code; and **a rolled-back release re-ships from the top of the ladder**, not from where it failed.

## 5. The repeatable part

Every manual step in a release is a future skipped step. The checklist lives in the repo, versioned, and each release follows it *by reading it*, not from memory; every incident adds its lesson to it (`postmortem` action items often land here). The endgame is automation — a pipeline that enforces the ladder and the gates — but a written checklist honestly followed is 80% of the value, today, for free. Version and changelog discipline ride along: tag what you shipped, write the changelog entry for the operator who diffs versions during an incident (what changed *behaviorally*, what config is new, what to watch).

## Anti-patterns

- The Friday-evening / pre-vacation deploy.
- Batching a week of merges into one release because releases are painful — pain is the signal to make releases *smaller and more frequent*, not rarer and bigger (frequency amortizes fear).
- Canarying without a baseline comparison, or promoting on "no pages yet" after five minutes.
- "Quick config change" outside the release process — config changes cause incidents at the same rate as code and deserve the same ladder.
- Migration and dependent code in one release step (see `migrate`; ship expand first).
- Declaring victory at deploy-success and looking away; the bug arrives with the first peak.
