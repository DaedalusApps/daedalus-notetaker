---
name: plain-language
description: Read a draft against the current published signs of AI writing and strike what matches. Use whenever prose will be read by a person, especially anything shipped publicly (docs, README, UI copy, PR and issue text, emails, applications). Complements `write` (process) and `ste-writing` (style); this one is detection and removal.
---

# Plain language

`write` decides what to say. `ste-writing` decides how plainly to say it. This skill does a
third job: it checks whether the result reads as machine-generated, and strikes what does.

The check is not taste. It is a set of published, dated observations about what current
models actually produce, held in `references/` and refreshed on a schedule.

## Sources, in priority order

1. **`references/wp-aisigns.md`** is the authority. A local cache of Wikipedia's
   `Wikipedia:Signs of AI writing`, which is community-maintained and revised
   continuously. It carries its own revision id, check date and refresh cadence.
2. **`references/ai-tells.md`** corroborates and extends it from six named sources,
   including one peer-reviewed corpus study (Matsui, *Perspectives on Medical Education*,
   2025) covering 135 terms across PubMed 2000 to 2024. Cite that one when a claim has to
   hold up.

Where the two disagree, prefer the Wikipedia cache and note the disagreement.

## The half-life rule

**Constructions outlive vocabulary.** "It's not X, it's Y" has been named as a tell every
year since 2023 and survived three years of prompt engineering against it. Word lists rot
fast: the 2023 register was Latinate and ornate (*delve, tapestry, pivotal*), the 2026
register is plain and monosyllabic (*quietly, shift, matters, lands*). Checking only for
*delve* now misses most current output.

So: check constructions first, vocabulary second, and treat any word list older than about
a month as suspect.

## Check order for a draft

1. AI-vocabulary density, and avoidance of plain copulas (*is*, *has*).
2. Negative parallelism and decorative rule-of-three.
3. Formatting: boldface stems, title case, emoji in headings.
4. Promotional puffery and "-ing" clauses that analyse nothing.
5. Does it read like the *signs of human writing* list: plain verbs, hedges, the occasional
   wordy construction a machine would have tidied away.

## Constructions to strike

- **Negative parallelism.** "It's not X, it's Y." The highest-value check here. Delete the
  construction and state the thing. It usually asserts nothing.
- **Rule of three.** "fast, simple and reliable." Real writing has lists of two and four.
  Factual enumerations are fine. Decorative triplets are not.
- **"Not only X, but also Y."** Split it or drop half.
- **Participle pile-ups.** Trailing clauses opening *highlighting, showcasing, reflecting,
  ensuring, underscoring, demonstrating*. Almost always deletable.
- **Connective chains.** *Moreover, Furthermore, Additionally* stacked across paragraphs.
- **Compulsive summary.** A closing paragraph opening *Overall* or *In conclusion* that
  restates what was just said.
- **Staccato runs.** Three or four clipped sentences in a row. This is the current default
  and it reads as generated. See the note on `ste-writing` below, which pulls the other way.

## Vocabulary to strike

Grep for these. Every hit is deleted or replaced with a plain word.

*delve, intricate, tapestry, pivotal, underscore, landscape, foster, testament, enhance,
crucial, robust, seamless, comprehensive, leverage (verb), navigate (figurative), realm,
showcase, spearhead, vital, essential, myriad, plethora, resonate, unlock, elevate,
transformative, cutting-edge, state-of-the-art, dynamic, synergy, boasts, commendable,
surpass, primarily, meticulous.*

Four of those (*boast, commendable, surpass, primarily*) come from the peer-reviewed study
and are missing from most popular lists, so they are worth an explicit check.

Then the 2026 plain register, where the tell is collocation and density rather than the
word itself: *quietly (building/transforming), a shift in, this matters because, shapes how,
lands, actually (as filler), real (value/impact), earn (trust/the right to), the work,
hold space, compound, send the signal.*

## Formatting tells

- Bold-stemmed bullets: every item opening with a **bolded label:** then text.
- Emoji in headings.
- Uniform bullet lengths forming a visual rectangle.
- Rhetorical colons: two-word fragment, colon, payoff.
- Title Case In Headings where the house style is sentence case.

## Two things that are NOT reliable tells

**The em dash.** Named repeatedly in popular advice and rejected by the sources here. The
mark is ordinary in edited prose, and counting it produces false positives against good
human writing. If you want a test, use the functional one: dashes appending a qualifying
clause rather than interrupting. There is also a second-order problem now that writers
avoid dashes defensively, so dash-absence is becoming its own signal. Do not drive the
count to zero.

*Individual users may still hold a zero-dash preference. That is a style preference, not
evidence, and should be recorded as one.*

**AI detectors.** Unreliable in both directions. No check in this skill depends on one. If
a detector and the read-aloud test disagree, the read-aloud test wins.

## Relationship to `ste-writing`

They pull against each other in one place and it needs saying out loud.

`ste-writing` asks for short active sentences. This skill flags runs of three or four
clipped sentences as the current generation's signature. Both are right. The resolution:

> Keep sentences short, but vary their length. One long sentence among short ones is human.
> Four short ones in a row is a fingerprint.

Length variation is the thing to preserve. Neither skill asks for uniformity.

## The test

Read it aloud. If you would not say it to someone standing in front of you, rewrite it.

## Refreshing the references

The lists expire. ACES 2026 expects nearly all specific tells to differ within a year, and
`ai-tells.md` says so in its own caveats.

Refresh when `references/wp-aisigns.md` is older than about a week, or `ai-tells.md` older
than about a month:

1. Check the live revision of `Wikipedia:Signs of AI writing` against the cached revision
   id. If only irrelevant sections changed, record that and stop. Diffing revisions is
   cheaper than re-reading the page.
2. For `ai-tells.md`, re-read the named sources, prefer ones with a named author and a
   date, and discard anything whose own prose has the cadence it claims to diagnose.
3. Rewrite the reference with each entry attributed and dated. Mark which parts are
   durable (constructions) and which are perishable (word lists).
4. Then sync the condensed lists in this SKILL.md (check order, constructions,
   vocabulary) with the refreshed references. They are copies and drift otherwise.
5. Never route around a blocked source. If a page cannot be fetched, say so in the file
   and ask for a manual copy rather than reaching for a mirror or scraper.
