---
description: Security audit — trace untrusted data flows, report only exploitable findings with concrete exploit scenarios
---

# /sec-audit

Argument: what to audit (a diff, files, or an area; defaults to the current diff plus its data paths).

## Steps

1. **Map**: entry points where untrusted data enters (handlers, CLI, uploads, webhooks, consumers) · sensitive sinks (queries, exec, file paths, eval/deserialize, auth decisions, logs) · secrets/PII locations. User-written data coming out of the DB is still untrusted.
2. **Trace** each entry point to its sinks. A grep hit is a candidate, not a finding — follow the value and the sanitization on every path. Categories in yield order: injection (SQL/shell/path/template) · authz on every path, IDOR, JWT validation · unsafe deserialization and eval · hardcoded secrets, secrets in logs/errors, disabled TLS, weak tokens · PII in logs, verbose errors, over-wide API responses · XSS/CSRF/SSRF/open redirects where applicable.
3. **Exclude** unless explicitly asked: DoS/resource exhaustion, rate limiting, missing hardening (headers, audit logs), theoretical races/timing, outdated-dependency CVEs (one "update X" line, not findings), memory safety in safe languages, test-only files, ReDoS.
4. **Gate**: a finding requires its exploit scenario — "attacker sends X → flows through Y unsanitized (file:line) → achieves Z". Traced end-to-end → CONFIRMED; one untraceable link → PLAUSIBLE + what would confirm; no scenario → delete.
5. Report `SEVERITY [CONFIRMED|PLAUSIBLE] path:line — vulnerability — exploit scenario — remediation`: BLOCKER = directly exploitable (RCE, auth bypass, live injection, exposed secrets); MAJOR = realistic preconditions; MINOR = contained defense-in-depth. Remediation names the standard fix (parameterized queries, allowlists, framework escaping). Clean report lists entry points traced and categories checked.
6. Don't fix during the audit — report first; fixes are a separate reviewed change. Committed secrets = rotate, not delete.
