---
description: Documentarian codebase research — map how it works with file:line evidence, zero critique, into a durable document
---

# /research-codebase

Argument: the research question ("how does X work", "where does Y live").

## Steps

1. **Document what IS.** No improvement suggestions, no problem-hunting, no refactor proposals, no root-cause analysis — unless explicitly asked. Surprising code is described neutrally; apparent live defects get one line at the end under "Observations (outside research scope)".
2. **Anchor**: restate the question concretely; sharpen vague asks into answerable sub-questions. Read every file the user mentioned — fully, first.
3. **Gather**: LOCATE (grep/glob sweeps for domain terms, entry points, config keys) then ANALYZE (read the located code, trace the actual flow — callers, next hops, data destinations; every answer gets file:line). Docs/ADRs/git history are historical context, labeled as claims — live code is the source of truth.
4. **Synthesize with evidence**: every factual claim carries path:line · connect the components · answer the actual questions explicitly · mark verified-by-tracing vs inferred-from-naming vs unexamined.
5. **Write the document** to `docs/research/YYYY-MM-DD-<topic>.md` (or the repo's notes convention; untouchable repo → deliver in the response) with frontmatter (date, git_commit — a map without its commit is a rumor, topic, status) and sections: Question · Summary · Detailed findings (file:line throughout) · Code references · Architecture notes · Historical context · Open questions. Follow-ups append a dated section to the same document.
6. Rules: existing research docs are a head start to re-verify at HEAD, never the answer · read load-bearing files fully · "traced A and B; C unexamined" beats a confident map with unmarked guesses.
