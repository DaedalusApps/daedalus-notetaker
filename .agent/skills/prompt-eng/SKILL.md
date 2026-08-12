---
name: prompt-eng
description: Prompts engineered like software — a clear contract with examples, tested against a case set before trusting, iterated on observed failures not vibes. Use when writing or debugging prompts, system prompts, agent instructions, or LLM-powered features; when a model's output quality is the problem to solve.
---

# Prompt Engineering

A prompt is a program executed by a stochastic interpreter — it deserves the same discipline as code: a spec, tests, debugging by evidence, and version control. Most bad prompts fail exactly like bad tickets fail a contractor: the instruction was ambiguous, the context was missing, the format was implied, and the success criteria lived in the author's head. And most prompt "engineering" fails like guess-driven debugging: rewrite, eyeball one output, repeat. The fixes are the same as in software — specify, then test, then iterate on failures.

## 1. Specify before you phrase

Write down, before wordsmithing anything: the task in one sentence · the exact output format (schema, length, fields — with a literal example of a perfect output) · the audience/consumer of the output (a parser? a human? another prompt?) · 3–5 representative inputs *including the ugly ones* (empty, adversarial, off-topic, too long) and what correct handling of each looks like. This is `spec` for a prompt, and it does the same job: most prompt failures are unstated requirements, discovered one production incident at a time.

## 2. Draft with the mechanics that reliably matter

- **Be direct and concrete.** Vague quality adjectives ("be helpful, thorough") steer nothing; observable behaviors do ("cite the section number for every claim; if no section supports it, say so"). Say what TO do, not only what to avoid — pure negations leave a vacuum where the failure was.
- **Show, don't only tell**: 1–5 examples of input→ideal output anchor format and judgment better than paragraphs of description. Choose them to span the space — including one that demonstrates the edge-case behavior ("question outside the docs → this exact refusal shape"). Examples are load-bearing: models imitate their *incidental* patterns too (length, tone, phrasing tics), so make the examples exactly what you want, incidentals included.
- **Structure the prompt** so instructions, context, and input are unambiguously delimited (XML tags, markdown sections). The classic injection-shaped bug — instructions and untrusted input concatenated in one blob — is also just a correctness bug.
- **Give the escape hatch.** For every "always do X", decide what happens when X is impossible, and say it ("if the input isn't valid JSON, output `{\"error\": ...}` instead of guessing"). Unspecified impossible-cases are where models hallucinate — they'd rather comply badly than disobey silently.
- **Put reasoning before conclusions** in the output order when the task needs judgment — a prompt that demands the verdict first gets post-hoc rationalization after it.
- Right-size the machinery: a stronger model with a clear contract often beats an elaborate prompt on a weaker one — and a long prompt dilutes its own load-bearing rules (every rule competes for attention; cut the ones that don't change behavior — same logic as `REVIEW.md`).

## 3. Test like it's code — because it is

One good output proves nothing; the interpreter is stochastic and the input space is wide.

- **Build the eval set first**: your step-1 cases plus real inputs as they accumulate — 10–20 cases catches the bulk of regressions; every production failure gets added, permanently (regression tests, exactly).
- **Grade against stated criteria**, per case: format parses? required content present? edge case handled? For subjective criteria, write a rubric — "good" isn't gradeable, "answers the question in ≤3 sentences without inventing citations" is. (Model-graded evals inherit the same rule: the grader needs the rubric.)
- **Run the set on every prompt change.** The defining prompt failure mode is whack-a-mole: the sentence fixing case 7 silently breaks cases 2 and 9. Only the full set catches the trade — an "improvement" verified on one case is a coin flip.
- Mind variance: for high-stakes evals, multiple runs per case; a case that passes 3-of-5 is a flake, and `ci-triage`'s rule applies — that's a real bug in the prompt's clarity, not noise to rerun away.

## 4. Debug by evidence

For each failing case, diagnose before editing — the categories have different fixes: **missing information** (model can't know it → add context) · **ambiguous instruction** (two readings; it took the other one → tighten wording) · **conflicting instructions** (rule A vs rule B; it picked — decide the priority yourself and encode it) · **capability ceiling** (clear ask, model can't → decompose the task, add worked examples, or upgrade the model — rewording won't cross a ceiling) · **example drift** (imitating an incidental pattern in your examples → fix the examples). One change per iteration, re-run the set, keep the change only if net-positive — `debug`'s loop, verbatim. If you can't say *why* a change helped, it's superstition and will regress.

## 5. Ship it like an artifact

Prompts are code: version them with the code that uses them, changelog the behavioral changes, and re-run the eval set on model upgrades — a model swap is a dependency major-version bump (`migrate` logic: read the release notes, test before trusting). In production, log inputs/outputs (the future eval set), and monitor the cheap invariants (parse rate, refusal rate, length drift) — prompt regressions arrive silently with traffic shifts, not with deploys.

## Anti-patterns

- Iterating on vibes: one eyeballed output per rewrite, no case set, no memory of what broke last time.
- The kitchen-sink prompt — twelve paragraphs of accumulated patches nobody dares remove. Prompts need `declutter` too; dead rules dilute live ones.
- Fixing a capability ceiling with louder wording (ALL CAPS, "CRITICAL", threats) — clarity scales, volume doesn't.
- Examples that contradict the instructions (the model follows the examples).
- Treating the happy path as the spec — the empty/adversarial/off-topic inputs are where LLM features actually fail in production.
- Prompt-injection blindness: any prompt that processes untrusted text needs the delimiter discipline of step 2 and a test case where the input *tries* to override the instructions.
