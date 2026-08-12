---
name: explain
description: Explanations engineered for the learner's current model — locate what they already know, build from it in checkable steps, name the misconception you're displacing, verify understanding instead of assuming it. Use when teaching a concept, explaining how something works or why a decision was made, answering "what does this mean", writing tutorials/onboarding docs, or mentoring.
---

# Explain

An explanation is not a transmission — it's an edit to a model that already exists in the learner's head. That's why correct-and-complete explanations routinely fail: they answer from the *expert's* model (bottom-up, definitions first, every caveat inline) at the *expert's* resolution, and the learner has nowhere to attach any of it. The craft is targeting: find where their model currently is, build the bridge from there, and check the bridge held. Completeness is the enemy of the first pass; a true-but-simplified model the learner can *use* beats a complete one they can't.

## 1. Locate the learner

Diagnose before dosing: what do they already know (so you can anchor to it), what do they *think* they know (misconceptions demand different treatment than gaps), and what do they need the understanding *for* (debugging it? choosing it? passing an interview?) — purpose sets both depth and angle. When you can ask, one question ("what's your mental model of X so far?") beats guessing; when you can't, infer from their vocabulary and calibrate mid-flight. The commonest failure is the **curse of knowledge** — the expert's inability to remember what not-knowing was like. Its tell: "obviously", "simply", "just". Delete those words; whatever follows them is exactly where the learner falls off.

## 2. Build from theirs, toward the structure

- **Lead with the point and the payoff** — what this thing is *for*, in one sentence, before any mechanism ("a mutex is how threads take turns; without it, two threads writing the same counter lose updates"). Motivation is the hook everything else hangs on.
- **Anchor to something they own**: an analogy from their domain, a system they've used, a problem they've felt. An analogy is scaffolding, not the building — flag where it breaks ("unlike a phone line, HTTP hangs up after every sentence") or the analogy's leftover implications become next year's misconception.
- **One conceptual step at a time, checkable**: each step lands before the next builds on it. Order by dependency, not by chronology or by how the expert discovered it.
- **Concrete before abstract, always**: a worked example with real values, *then* the general rule. The example is what they'll actually reconstruct from memory; the abstraction is what compresses it. Two contrasting examples (one that is, one that almost-is-but-isn't) draw the concept's boundary better than any definition.
- **Displace misconceptions explicitly.** A misconception isn't a hole — it's occupied territory; new information gets reinterpreted to fit it. Name it, show where it predicts wrongly, *then* install the replacement ("you'd expect the list to copy here — it doesn't, both names point at the same list, watch:").

## 3. Simplify honestly

Every good explanation lies a little; the discipline is lying *knowingly*. Simplifications that omit detail are fine ("we'll ignore caching for now" — flagged, so the learner knows there's a door there); simplifications that will have to be *unlearned* are not. Match resolution to purpose — the driver's model of an engine differs from the mechanic's, and both are correct — and resist the expert's urge to append every edge case: caveats delivered before the core model exists just erode confidence in the model. Park them: "there are exceptions; get the main case solid first."

## 4. Verify the landing

"Does that make sense?" measures politeness, not understanding — nodding is the null response. Real checks make the learner *generate*: have them restate it in their words, predict an outcome ("what happens if I close the channel here?"), apply it one step beyond the examples, or explain why the naive approach fails. Wrong answers are the diagnostic gold — they show precisely where the model diverged, which is where the next explanation round targets. In writing, where you can't check, build the check in: pose the question, pause, answer it — and give the reader a way to test themselves ("before reading on: what would this print?").

## 5. Close the loop

End with the shape of what's next: what was deliberately simplified (the parked caveats get their pointers), where this understanding runs out, and the one thing to remember if they forget the rest. For durable artifacts (tutorials, onboarding docs, ADR explanations), the `write` skill's revision pass applies — and the best test of a written explanation is watching one real novice traverse it and noting where they stall.

## Anti-patterns

- The completeness dump — answering a one-sentence question with the full taxonomy.
- Explaining the implementation when they asked for the concept (or vice versa).
- Vocabulary as a gate: each undefined term is a fork where you lose readers; define at first use or don't use it.
- The analogy left unbounded, colonizing the concept.
- Condescension-proofing by over-simplifying to an expert (mis-locating cuts both ways — depth-check early).
- Mistaking your fluency for their understanding: if they can't *do* anything new after the explanation, it didn't land — however good it felt to deliver.
