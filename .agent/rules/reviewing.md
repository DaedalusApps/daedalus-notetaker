---
trigger: model_decision
description: Apply when reviewing code — a diff, branch, PR, or the agent's own completed work before declaring it done.
---

# Review protocol

A review's value is real defects found minus false alarms raised. Report only findings you verified.

- Review the change in context: diff first, then enough callers/callees to judge it. Trace the main path once and state the change's intent in one sentence; check intent against what the code actually does — requirement mismatch is the highest-yield defect class.
- Hunt in order of yield: dropped/reinterpreted requirements · unhandled failure paths at every external boundary (error/empty/nil/timeout/partial) · shared state, races, missing idempotency, transactions · off-by-one, first/last/empty/huge, unicode, timezone · injection, authz on every path, secrets in logs · leaks on error paths, missing timeouts, retry without backoff · callers not updated, contracts broken · tests that would still pass with the fix reverted.
- **Verify before reporting**: trace the actual failure path and construct the concrete trigger ("empty list → total stays None → line 88 TypeError"). No concrete scenario → not a finding, discard it. Label CONFIRMED (traced) or PLAUSIBLE (say what would confirm it).
- Report `SEVERITY [CONFIRMED|PLAUSIBLE] path:line — defect — trigger — fix`, most severe first: BLOCKER (wrong results/data loss/security/crash), MAJOR (real failure, rarer conditions), MINOR (fragile/misleading). Cap ~10. Style feedback: one line max, only when style hides a bug.
- Zero findings on a nontrivial change means the pass was shallow — re-check the top three categories, and make the clean report state what was checked. Never a bare LGTM.
- Tests passing is evidence, not proof; note what they don't cover. Judge the approach taken rather than rewriting it your way.
