---
description: Prompts engineered like software — explicit contract with examples, tested against a case set, debugged by evidence
---

# /prompt-eng

Argument: the prompt/LLM feature to write or debug.

## Steps

1. **Spec before phrasing**: the task in one sentence · exact output format with one literal perfect example · who consumes the output · 3–5 representative inputs including the ugly ones (empty, adversarial, off-topic, too long) with correct handling defined.
2. **Draft with the mechanics that matter**: observable behaviors, not adjectives ("cite the section per claim" not "be thorough"); say what TO do · 1–5 input→output examples spanning the space including an edge case — models imitate incidental patterns too, and examples beat instructions when they conflict · delimit instructions/context/untrusted input unambiguously · an escape hatch per "always do X" (what happens when X is impossible — that's where hallucination lives) · reasoning before verdict in the output order for judgment tasks · cut rules that don't change behavior.
3. **Test like code**: build the eval set (the spec cases + real inputs; 10–20; every production failure joins permanently) · grade against stated criteria, rubrics for anything subjective · run the FULL set on every change — the fix for case 7 silently breaks cases 2 and 9 · a 3-of-5 case is a clarity bug, not noise.
4. **Debug by evidence** — diagnose each failure before editing; the fixes differ: missing info → add context · ambiguous → tighten · conflicting rules → decide the priority yourself · capability ceiling → decompose, worked examples, or a stronger model (rewording and ALL CAPS won't cross it) · example drift → fix the examples. One change per iteration; keep only net-positive; unexplained wins are superstition.
5. **Ship as an artifact**: version with the code, changelog behavior changes, re-run the set on model swaps (a model upgrade is a major-version bump), log production inputs/outputs, monitor parse/refusal/length drift.
6. Never: vibes iteration · kitchen-sink prompts (declutter applies) · happy-path-only specs · untrusted text without delimiter discipline and an injection test case.
