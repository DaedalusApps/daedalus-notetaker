---
name: research-codebase
description: Documentarian codebase research — answer "how does X work / where does Y live" by mapping what exists, with file:line evidence, zero critique, and a persistent research document a later session can build on. Use when asked how something works, where something lives, what the current behavior is, or to research/document a codebase area before planning changes. (Adapted from Humanlayer's research_codebase command.)
---

# Research Codebase

This skill answers questions about a codebase **as it exists today** — a technical map, not a review. The stance is the whole skill: the moment research drifts into critique ("this should be refactored"), two things break — the map becomes untrustworthy (was that path omitted or disapproved of?), and the reader who asked "how does auth work" gets an opinion instead of an answer. Judgment has its own skills (`deep-review`, `architect`); this one is a documentarian. Its other differentiator from ad-hoc exploration: the output is a *durable document* with evidence, so the research is done once, not re-done every session.

## The prime directive

Document what IS. Do not suggest improvements, identify problems, propose refactors, or perform root-cause analysis — **unless the user explicitly asks**. Surprising code gets described neutrally ("retries are unbounded here; the loop exits only on success — `client.py:88`"), and the reader draws conclusions. If you notice something that looks like a live defect, one line at the end under "Observations (outside research scope)" — not woven into the map.

## 1. Anchor on the question

Restate the research question concretely; if it's vague ("research the auth stuff"), sharpen it into the questions you'll actually answer ("how are sessions created, validated, expired; where are the entry points?") and confirm direction cheaply. **Read any files the user mentioned — fully, before anything else.** They're the anchor for decomposition, and skimming them first is how research answers a subtly different question than the one asked.

## 2. Decompose and gather

Split the question into parallelizable sub-questions along its natural seams (per component, per lifecycle stage, per layer). Two gathering modes, in order:

- **Locate** — where does the relevant code live? Broad, cheap sweeps: grep/glob for the domain terms, entry points, config keys. In Claude Code, fan out `Explore` subagents for independent sub-questions to keep the main context clean for synthesis; elsewhere, do the sweeps sequentially.
- **Analyze** — for the located hot spots, read the actual code and trace the actual flow: who calls this, what happens next, where does the data go. This is where answers get their `file:line` evidence.

Also mine the repo's own memory where it exists — docs/, ADRs, design notes, and git history (`git log --follow` on the key file; the PR that introduced it) — as *historical context*, clearly labeled as such: **the live code is the source of truth; documents are claims about it.**

## 3. Synthesize with evidence

Every factual claim in the output carries a `path/to/file.py:123` reference — the reference is what makes the document *checkable* and lets a reader jump straight to the code. Connect the components (what calls what, what data flows where); answer the user's actual questions explicitly rather than leaving the reader to derive answers from the map; and mark the boundaries honestly: what you verified by reading/tracing vs. inferred from naming, and what remains unexamined ("did not trace the async path").

## 4. Write the research document

Persist it — default `docs/research/YYYY-MM-DD-<topic>.md` (follow the repo's existing notes convention if one exists; if the repo shouldn't be touched, deliver the same document in the response):

```markdown
---
date: <ISO date>
git_commit: <hash researched — the map is of this commit>
topic: "<the question>"
status: complete
---
## Question · ## Summary (the answer, 3–6 sentences)
## Detailed findings (per component: what exists, how it connects — file:line throughout)
## Code references (the jump table: path:line — one-line description)
## Architecture notes (patterns and conventions observed — described, not graded)
## Historical context (from docs/ADRs/git, labeled as claims)
## Open questions (what wasn't traced or couldn't be determined)
```

The `git_commit` line matters: code moves, and a map without its commit is a rumor. Present the summary conversationally; the document is the artifact. Follow-up questions **append** to the same document (new dated section) rather than spawning siblings — one topic, one growing document.

## Rules

- Fresh research over stale documents: an existing research doc on the topic is a head start and a diff target ("still true at HEAD?"), never the answer itself.
- Read files fully when they're load-bearing for the answer — sampling a file you're documenting is how "the map says X but the code does Y" happens.
- Precision beats coverage: "I traced A and B; C is unexamined" outranks a confident map with unmarked guesses. The Open-questions section is a feature, not an apology.
- Keep the synthesis context clean: gathering is delegable (subagents, sweeps); synthesis — connecting findings and writing the document — is the main thread's job.
- This skill feeds the others: run it before `spec`/`architect`/`breakdown` on unfamiliar ground, and let `onboard` use its output as the trail map. When research findings make change-work start, the stance switches — critique reactivates outside this skill.
