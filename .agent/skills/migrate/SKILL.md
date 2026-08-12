---
name: migrate
description: Upgrades and migrations that can't strand you — read the breaking changes first, move in reversible steps, one major version at a time, expand-migrate-contract for data. Use for dependency/framework/language upgrades, API version bumps, database schema or data migrations, and platform moves.
---

# Migrate

A migration's defining risk is the point of no return crossed by accident. Old and new must coexist until proof arrives, because the rollback you didn't design is the outage you'll improvise. Every step below serves one property: **at any moment, you can stop, and the system still works.**

## 1. Read before you touch

- Read the changelog / release notes / migration guide for every version you'll cross — the breaking-changes list is the spec for this task. Verify it for *your* usage: grep the codebase for each deprecated/changed API and list the actual call sites.
- Inventory blast radius: direct usage, transitive dependents, serialized data (caches, queues, DB rows) written in the old format, other services pinned to the old behavior.
- Write down the **rollback plan before starting**: how do you get back from each step, and which step (if any) is one-way? One-way steps get flagged and scheduled last.

## 2. The staging rules

- **One major version at a time.** v2→v4 is two migrations; skipping intermediate majors means debugging two sets of breaking changes simultaneously with no known-good midpoint.
- **One dependency per PR/commit.** A lockfile diff touching forty packages is unreviewable and unbisectable; when something breaks a week later, `git bisect` should land on *one* upgrade.
- **Mechanical before behavioral.** Run the codemod / rename / import-rewrite as its own commit with no hand edits, then hand-fix the rest separately — reviewers can skim the mechanical commit and scrutinize the judgment one.
- Test net first: the suite must be green *before* the upgrade, or you can't attribute failures to it (`refactor`'s prerequisite, same reason).

## 3. Expand → migrate → contract (data and interfaces)

For anything with persisted state or independent deployers (DB schemas, message formats, public APIs), never change in place:

1. **Expand** — add the new column/field/endpoint alongside the old. Both work. Deploy.
2. **Migrate** — write to both / backfill / move readers to the new one. Verify counts and spot-check values *before* proceeding: the verification here is the last cheap moment to catch corruption.
3. **Contract** — remove the old path only after nothing reads it (measure — logs/metrics, not belief), one deploy *after* you think it's safe.

Each phase is separately deployable and separately revertible. Schema changes that lock large tables get checked for online-migration strategy before running against production-sized data — the migration that worked on the dev database and locks production for an hour was tested on the wrong axis.

## 4. Verify like it's a release

- Full suite green, plus targeted tests on the call sites from step 1's inventory — that list is your checklist; walk it.
- Runtime proof, not just compile/type proof: deprecation *warnings* are next year's breakage — burn them down now or ticket them.
- For data: row counts, checksums or sampled field-level diffs between old and new, and the application reading the migrated data — not just the migration script exiting 0.
- Soak where possible: run the migrated system on real traffic/data in a non-critical lane before cutting over.

## 5. The long-tail rule

A migration is done when the *old thing is gone* — the compat shim, the dual-write, the pinned old version, the `TODO: remove after migration`. Half-finished migrations are the worst codebase state: two ways to do everything, forever. Before calling it done, grep for the old API one last time; schedule the contract step with a date, not "later".

## Report

Versions crossed and breaking changes that applied to us · call sites changed (count, from the inventory) · rollback status per step (still reversible / one-way crossed at step N) · verification evidence (suite result, data checks, quoted) · remaining long-tail with owner/date. Close with `prove`.

## Anti-patterns

- Upgrading because it's Tuesday. Every upgrade needs a reason (security fix, needed feature, EOL) — churn has a real cost and "latest" is not a reason.
- Doing the refactor you've been wanting *inside* the migration commit. Two risky changes in one diff attribute each other's failures.
- Trusting the types/compiler as the whole test ("it builds on v3").
- Backfilling and contracting in the same deploy.
- Silencing deprecation warnings instead of resolving them.
- The 40-package lockfile "upgrade everything" commit.
