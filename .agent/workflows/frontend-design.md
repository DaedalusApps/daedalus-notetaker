---
description: Make interfaces look designed, not defaulted — tokens before components, hierarchy before decoration, design every state, verify by looking
---

# /frontend-design

Argument: the UI/screen/component to design or restyle.

## Steps

1. **Design language first**: pick 2–3 personality adjectives from the product's actual purpose, then derive tokens before any component — type scale (one ratio, ≤2 typefaces), spacing scale (4/8px steps), color system (neutral ramp + one accent), radius/shadow scales. Any hard-coded value off-scale is a design bug.
2. **Hierarchy before decoration**: one primary action per screen; everything else subordinate. Spend size/weight/color/position in proportion to importance. Squint test — what still pops must be what matters. No decoration until this passes.
3. **Typography and spacing carry the design**: line length 45–75ch · line-height ~1.5 body, tighter headings · whitespace groups (proximity beats borders) · everything grid-aligned. Doubling whitespace is the cheapest upgrade.
4. **Color is a system**: neutrals do the work; accent scarce (primary actions only) · contrast ≥ 4.5:1 for text · never meaning by color alone · light and dark designed together from the start.
5. **Design the real states**: empty, loading, error, overflow (47-char name, 0 items, 10,000 items) — not just the happy path. Realistic content, never lorem ipsum.
6. **Responsive is layout**: content-driven breakpoints · no horizontal body scroll ever · touch targets ≥ 44px · test at 320px and ultrawide.
7. **Escape the default look**: avoid the clichés (purple-gradient hero, identical rounded 3-card grid, everything centered, emoji icons, shadow soup). Make one deliberate distinctive move; restraint everywhere else.
8. **Verify by looking**: screenshot the rendered result (/prove for pixels) · resize test · keyboard-tab test (focus visible) · squint test · both themes · real-data states. "It works" is not a design verdict.
