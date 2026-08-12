---
name: frontend-design
description: Make interfaces look designed, not defaulted — commit to a design language before the first component, spend hierarchy before decoration, design the real states, and verify by looking at the rendered result. Use when building or restyling any UI — web app, landing page, dashboard, component library — or when a working interface "looks off" and needs design quality.
---

# Frontend Design

Bad frontend design is rarely a talent problem. It's three skipped decisions: no design language is chosen so framework defaults choose it, screens get decorated before hierarchy is established, and the result never gets looked at. Each is cheap to fix and each is a distinct failure mode — fix them in order.

## 1. Commit to a design language first

Pick 2–3 personality adjectives from the product's actual purpose — a trading dashboard is not a bakery site — and derive tokens from them before writing a single component: a type scale (one ratio, at most two typefaces), a spacing scale (4/8px steps), a color system (a neutral ramp doing most of the work plus one accent), and radius/shadow scales. Every hard-coded magic value inside a component — a stray `17px`, an ad hoc `#3a3a3a` — is a design bug: if a value isn't on a scale, it's a decision nobody made, and the framework default made it instead. Pinning the product's purpose first is `spec` applied to visuals; when several plausible directions exist, generate variations before committing (`brainstorm`).

## 2. Hierarchy before decoration

Every screen has one primary action; everything else is visually subordinate to it. Spend size, weight, color, and position on each element *in proportion to its importance* — if everything is bold, nothing is. Run the squint test before styling further: blur your eyes at the screen; what still pops must be what actually matters. Decoration (shadows, gradients, icons) applied before hierarchy is settled just adds noise on top of an unresolved layout.

## 3. Typography and spacing carry the design

Most "looks amateur" verdicts trace back to spacing and type, not color: line length 45–75 characters, line-height around 1.5 for body text and tighter for headings, whitespace used to *group* — related things close together, unrelated things far apart, proximity beating borders as a grouping signal. Align everything to a grid; unaligned edges read as carelessness even when nothing else is wrong. Doubling the whitespace on a cramped screen is the cheapest visual upgrade there is.

## 4. Color is a system, not a mood

Neutrals carry the interface; the accent is scarce, reserved for primary actions and the handful of things that must be found instantly. Text contrast is at least 4.5:1 (WCAG AA), meaning is never carried by color alone (add an icon or a label), and light and dark themes are designed together from the start — a dark theme bolted on after the fact always shows in the details: borders, disabled states, shadows that vanish, chart colors that scream.

## 5. Design the real states

The happy-path mock with perfect fake data is the easiest 20% of the job. Design the empty state (first-run is most users' actual first impression of the product), the loading state, the error state, and the overflow states — the 47-character name that wraps, the list with 0 items, the list with 10,000. Use realistic content while designing, never lorem ipsum: content-shaped design is what survives contact with production data; placeholder text hides exactly the layout problems real content exposes.

## 6. Responsive is layout, not shrinking

Breakpoints are driven by where the content itself breaks, not by fixed device widths. No horizontal scroll on the page body, ever. Touch targets are at least 44px. Test at 320px and at ultrawide — the middle of the range takes care of itself if the extremes hold.

## 7. Escape the default look

Name the clichés so they're recognizable and avoidable: the purple-gradient hero, identical rounded cards in a 3-column grid, everything centered, emoji standing in for icons, shadow soup. Make one deliberate distinctive move — a strong typeface, an opinionated accent, an asymmetric layout — and hold restraint everywhere else; the restraint is what makes the one move read as intentional rather than accidental.

## 8. Verify by looking

UI work is unverified until it's rendered and looked at — screenshot it (this is `prove` for pixels). Then: resize test (does it hold at narrow and wide), keyboard-tab test (focus visible on every interactive element), squint test (the right thing still pops), both themes, and the real-data states from step 5. "It works" is not a design verdict; "I looked at it in both themes at three widths and it holds" is.

## Anti-patterns

- Styling components before hierarchy is decided — you'll restyle everything once the layout changes.
- Fixing a value pixel-by-pixel in code review instead of fixing the scale it should have come from.
- Skipping the design-language step because "it's just an internal tool" — internal tools compound the same defaults for years.
- Treating "it renders without errors" as "it looks designed".
- Designing only the happy path, then discovering the empty and error states in production.
