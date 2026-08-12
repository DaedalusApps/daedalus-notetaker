---
name: sec-audit
description: Security review of a diff or codebase — trace untrusted data flows, report only high-confidence exploitable vulnerabilities with concrete exploit scenarios, filter the classic false-positive categories. Use when asked for a security review/audit, before shipping auth/payment/user-input handling code, or when handling data from untrusted sources.
---

# Security Audit

A security report is judged by a harsher standard than a code review: every false positive trains the reader to ignore the report, and the one real vulnerability drowns. So this protocol reports **only what an attacker could actually do** — each finding needs a concrete exploit scenario, and whole categories of theoretical findings are excluded up front. It is `deep-review`'s hostile sibling: same verification gate, attacker's mindset.

## 1. Map the attack surface

Before hunting, know the terrain — a vulnerability is a *path* from attacker-controlled input to a sensitive operation:

- **Entry points**: HTTP handlers, CLI args, file uploads, webhooks, message consumers, environment/config that deployment users control.
- **Trust boundaries**: where does data stop being "ours"? Database contents written by users are still untrusted when they come back out.
- **Crown jewels**: credentials, PII, payment data, admin operations, code execution.

For a diff-scoped audit, still read beyond the diff: the vulnerability is often in how changed code composes with existing validation (or its absence).

## 2. Trace, don't pattern-match

Grep hits ("uses `eval`", "string-concatenated SQL") are candidates, not findings. For each candidate, trace the actual data flow: *where does the value come from, what sanitization does it pass through on **every** path, and what can an attacker make the sink do?* Most scanner noise dies here — the value turns out to be a compile-time constant, or validated upstream.

## 3. The hunt — categories in yield order

1. **Injection** — SQL/NoSQL, shell/command, path traversal, template, XXE, LDAP; anywhere untrusted data is concatenated into an interpreter's input.
2. **AuthN/AuthZ** — authorization checked on *every* path (not just the UI route), IDOR (object IDs authorized against the caller, not just authenticated), privilege escalation, session fixation, JWT validation (alg confusion, missing expiry/signature checks).
3. **Code execution & deserialization** — unsafe deserialization (pickle, YAML `load`, Java native), `eval`/dynamic import of tainted strings, prototype pollution.
4. **Secrets & crypto** — hardcoded credentials, secrets in logs/error messages/URLs, weak or homemade crypto, disabled certificate verification, predictable tokens (non-CSPRNG).
5. **Data exposure** — PII/credentials in logs, verbose errors leaking internals to clients, mass assignment, API responses returning more fields than the client needs.
6. **Web-specific** (when applicable) — XSS (reflected/stored/DOM), CSRF on state-changing endpoints, SSRF on user-supplied URLs, open redirects, cookie flags.

## 4. Do NOT report — the exclusion list

These categories are noise in almost every audit; excluding them is what keeps the report readable. Skip: denial-of-service and resource exhaustion · rate-limiting gaps · missing hardening that isn't a concrete vulnerability (absent security headers, missing audit logs) · theoretical race conditions or timing attacks without a practical exploit · vulnerabilities in outdated third-party libraries (report as one dependency-update line, not per-CVE findings) · memory safety in memory-safe languages · findings in test-only files · log spoofing · ReDoS/regex injection.

If the user explicitly asks about one of these (e.g. a DoS assessment), it is in scope — the default exclusion exists to protect signal, not to forbid the topic.

## 5. The gate: exploit scenario or silence

A finding may be reported only if you can write its exploit scenario: *attacker does X → data flows through Y unsanitized → attacker achieves Z*. Concrete, with the actual path and line numbers. Then label it:

- **CONFIRMED** — you traced the tainted flow end-to-end and can state the malicious input.
- **PLAUSIBLE** — the sink is real but one link is untraceable (dynamic dispatch, external config); say exactly what would confirm it.

No scenario → not a finding. "This looks unsafe" is a vibe.

## 6. Report

```
SEVERITY [CONFIRMED|PLAUSIBLE] path:line — vulnerability — exploit scenario — remediation
```

- **BLOCKER** — directly exploitable: RCE, auth bypass, injection reaching real data, secrets exposed.
- **MAJOR** — exploitable under realistic preconditions (needs an account, a second bug, a misconfig that's common).
- **MINOR** — real weakness, contained blast radius; defense-in-depth worth doing.

Most severe first. Each remediation names the standard fix (parameterized queries, allowlist validation, framework escaping) — never invent custom sanitizers. A clean report states which entry points were traced and which categories were checked; "no issues found" without coverage is not an audit.

## Rules

- Validate at trust boundaries with **allowlists**; blocklists lose.
- The absence of a vulnerability you looked for is worth one line; the absence of looking is worth zero.
- Don't fix during the audit — report first, fix as a separate reviewed change (fixes under audit pressure skip design).
- Secrets already committed to git history count as exposed — rotation, not deletion, is the remediation.
- This audits code you/your team are authorized to audit. Findings and exploit scenarios stay in the report, at the level of detail the fix requires.

After fixes land, re-trace the fixed flows (`prove`); for the routine per-diff security pass, category 5 of `deep-review` already covers the basics — reach for `sec-audit` when security *is* the task.
