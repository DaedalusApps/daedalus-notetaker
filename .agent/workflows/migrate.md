---
description: Upgrades and migrations in reversible steps — breaking changes first, one major at a time, expand-migrate-contract
---

# /migrate

Argument: what to upgrade/migrate (dependency + target version, framework, schema change).

## Steps

1. **Read first**: changelog/migration guide for every version crossed — the breaking list is the spec. Grep for each changed API; the call-site list is your checklist. Write the rollback plan before starting; flag one-way steps and schedule them last.
2. **Stage**: one major version at a time · one dependency per commit (40-package lockfile diffs are unbisectable) · mechanical changes (codemods, renames) in their own zero-hand-edit commit · suite green BEFORE upgrading or failures can't be attributed.
3. **Data/interfaces — expand → migrate → contract**: add the new alongside the old (both work, deploy) → dual-write/backfill and verify counts + sampled values before proceeding → remove the old only after measurement shows nothing reads it, one deploy later. Check schema changes for locks against production-sized data.
4. **Verify**: suite green + walk the call-site checklist · runtime proof, not compile proof · deprecation warnings resolved or ticketed · data proven by row counts, sampled field diffs, and the application reading it — script exit 0 proves nothing.
5. **Finish the long tail**: done = the old thing is gone (shims, dual-writes, pins, "TODO remove"). Final grep for the old API; contract step gets a date, never "later".
6. Report: versions crossed + applicable breaking changes · call sites changed · rollback status per step · verification evidence (quoted) · remaining long-tail with date. No refactoring inside migration commits; every upgrade needs a stated reason.
