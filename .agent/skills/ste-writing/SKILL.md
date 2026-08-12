---
name: ste-writing
description: Write all prose in the spirit of ASD-STE100 Simplified Technical English — one word per idea, short active-voice sentences, one topic per paragraph. Applies to docs, commit messages, PR descriptions, reports, replies, and code comments. Use at the start of any session, and whenever you write prose a person will read.
---

# STE Writing

Write all prose in the spirit of ASD-STE100 Simplified Technical English. The goal is text a tired reader parses once, correctly. This applies to every kind of prose: docs, commit messages, PR descriptions, reports, chat replies, and code comments. It does not apply to code itself, identifiers, quoted output, or proper nouns.

This is the *spirit* of STE, not full dictionary compliance. Do not look words up against the approved list. Do apply the rules below to everything you write.

## The rules

**One term per concept, one concept per term.** Pick one word for each concept and keep it for the whole document — do not call it "the worker" in paragraph one and "the job runner" in paragraph three. Do not reuse that word for a second concept either: if "build" names the CI artifact, do not also use "build" as a verb for writing code.

**Short sentences.** Keep instructions to 20 words or less. Keep descriptive sentences to 25 words or less. If a sentence needs a semicolon or a second clause, split it.

Short is not the same as uniform. **Vary the length.** A ceiling on length is not a target to hit every time; see [`plain-language`](../plain-language/) for why a run of clipped sentences is itself a tell.

**Active voice, named actor.** Write "Run the tests", not "The tests should be run". Write "The parser rejects empty input", not "Empty input is rejected". Every sentence says who does what, and steps are commands: "Open the file. Delete the block. Run the check."

**Simple verbs.** Use "use", not "utilize". Use "start", not "initiate". Use "show", not "demonstrate" or "surface". Prefer the plain word whenever it carries the same meaning.

**One topic per paragraph.** Start a new paragraph when the topic changes. Keep paragraphs to six sentences or less. Put the most important sentence first.

## What the rules do not override

- **Precision wins.** If the plain word loses a needed technical distinction, keep the technical word and use it consistently.
- **Names are literal.** Copy API names, flags, identifiers, error text, and proper nouns character for character. Never simplify them.
- **Required formats win.** A repo's commit convention, PR template, or issue format takes priority. Apply STE to the free text inside it.

## Self-check before you send

Reread what you wrote and fix these, in order:
1. Any instruction over 20 words → split it.
2. Any passive sentence → name the actor and rewrite.
3. Any concept with two names → pick one and replace the other everywhere.
4. Any paragraph with two topics → split it.
