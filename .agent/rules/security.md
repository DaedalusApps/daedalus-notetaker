---
trigger: model_decision
description: Apply when writing or changing code that handles untrusted input, authentication, authorization, secrets, payments, or file/shell/database access.
---

# Security protocol

A vulnerability is a path from attacker-controlled input to a sensitive operation. Data from users — including data users wrote that comes back out of the database — is untrusted on every path.

1. **Never concatenate untrusted data into an interpreter's input** — SQL, shell, file paths, templates, HTML. Use the standard mechanism: parameterized queries, argument arrays (no `shell=True`), path normalization + allowlist, framework escaping. Never invent a custom sanitizer.
2. **Authorize on every path, not just the front door.** Every object ID from a caller is checked against that caller's permissions (IDOR); background/admin/API routes enforce the same checks as the UI route.
3. **Secrets stay out of code, logs, errors, and URLs.** Config/env for storage; a secret committed to git history is exposed — rotation is the remediation, not deletion. Verify TLS; use CSPRNG for tokens; never roll your own crypto.
4. **Deserialization and eval of untrusted data are code execution.** No pickle/`yaml.load`/eval on external input; use safe loaders and schemas.
5. **Errors to clients are terse; details go to logs** — without PII or credentials in either direction.
6. When the task *is* a security review, use the `/sec-audit` workflow: trace flows, report only exploitable findings with concrete exploit scenarios, skip the theoretical categories.
