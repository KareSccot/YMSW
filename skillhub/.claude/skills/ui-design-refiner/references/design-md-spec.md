# DESIGN.md authoring reference

Condensed from the [google-labs-code DESIGN.md spec](https://github.com/google-labs-code/design.md/blob/main/docs/spec.md). Use it to emit a valid `DESIGN.md` at the end of an optimize pass, without needing to re-fetch the spec.

`DESIGN.md` is a plain-text, self-contained record of a design system: YAML front matter holds machine-readable **tokens** (the normative values), and the markdown body holds human-readable **rationale**. Prose may use descriptive color names ("Midnight Forest Green") as long as they map to a systematic token (`primary`). Tokens convert cleanly to `tokens.json`, Figma variables, and Tailwind theme configs.

Write this from the design you actually converged on during the loop — not an idealized system. Every token should be traceable to a decision you made and can defend with evidence. Do not invent scales the product never used.

## Front matter (tokens)

Begins and ends with a line containing exactly `---`.

```yaml
version: alpha
name: <system name>
description: <one line, optional>
colors:
  <token>: <CSS color>          # hex recommended; any valid CSS color allowed
typography:
  <token>:
    fontFamily: <string>
    fontSize: <px|em|rem>
    fontWeight: <number>
    lineHeight: <number or dimension>   # unitless multiplier preferred
    letterSpacing: <em|px>              # optional
rounded:
  <scale>: <dimension>          # none / sm / md / lg / xl / full
spacing:
  <scale>: <dimension|number>   # 8px-based rhythm is common
components:
  <component>:
    <prop>: <literal or {token.reference}>
```

Rules that matter:

- **Token references** use `{path.to.token}`, e.g. `{colors.primary}`. Outside `components`, a reference must point at a primitive value, not a group. Inside `components`, composite references like `{typography.label-md}` are allowed.
- **fontWeight** is numeric (`400`, `600`); quoted or bare both fine.
- **lineHeight** prefers a unitless multiplier (`1.6`).
- At least a `primary` color must exist. Add `secondary`, `tertiary`, `neutral`, `surface`, `on-surface`, `error` as the design needs them.
- Most systems have 9–15 typography levels. Common component props: `backgroundColor`, `textColor`, `typography`, `rounded`, `padding`, `size`, `height`, `width`. State variants use related keys (`button-primary`, `button-primary-hover`, `button-primary-active`).
- Duplicate `## Colors` (or any duplicate section) makes the file invalid. Unknown section names, token names, and component props are tolerated by consumers, so domain-specific additions are fine — but don't add noise.

## Body sections (in this order; omit any that don't apply)

1. **Overview** (a.k.a. "Brand & Style") — brand personality, audience, and the emotional response the UI should evoke. This is where the product's derived personality and anti-personality live.
2. **Colors** — palette roles and why each exists.
3. **Typography** — families, weights, and the voice each level carries.
4. **Layout** (a.k.a. "Layout & Spacing") — grid/margin model and the spacing rhythm.
5. **Elevation & Depth** — how hierarchy is conveyed (tonal layers, borders, shadow budget).
6. **Shapes** — corner language and geometric character.
7. **Components** — per-atom guidance and variants.
8. **Do's and Don'ts** — guardrails as short imperative lines.

Sections use `##` headings. An optional `#` title may lead the file.

## Minimal valid template

```markdown
---
version: alpha
name: Calm Review Console
colors:
  primary: "#1A1C1E"
  secondary: "#6C7278"
  accent: "#B8422E"
  surface: "#FFFFFF"
  neutral: "#F7F5F2"
  error: "#B00020"
typography:
  headline-md:
    fontFamily: Public Sans
    fontSize: 24px
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: -0.01em
  body-md:
    fontFamily: Public Sans
    fontSize: 15px
    fontWeight: 400
    lineHeight: 1.6
  label-caps:
    fontFamily: Public Sans
    fontSize: 12px
    fontWeight: 500
    lineHeight: 1
    letterSpacing: 0.08em
rounded:
  sm: 4px
  md: 8px
  full: 9999px
spacing:
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 40px
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.sm}"
    padding: 12px
  button-primary-hover:
    backgroundColor: "{colors.secondary}"
---

# Calm Review Console

## Overview
[Personality and anti-personality, derived from task/audience/risk — not borrowed from a brand.]

## Colors
[Each role, one line, tied to how it is used and why it is scarce.]

## Typography
[Families, weights, and the reading role each level plays.]

## Layout
[Grid/margin model and the spacing rhythm that expresses relationship.]

## Elevation & Depth
[How hierarchy is signalled; shadow/border budget.]

## Shapes
[Corner language and its character.]

## Components
[Key atoms and their state variants.]

## Do's and Don'ts
- Do reserve the accent for the single primary action per view.
- Don't wrap every group in a card.
- Do keep WCAG AA contrast (4.5:1 for body text).
- Don't exceed two type weights on one screen.
```

## Quality bar before emitting

- Tokens reflect the converged design, and prose explains *why* each choice serves the task — the file should read as evidence, not decoration.
- Personality in Overview matches the anti-personality constraints you set at the start.
- Do's/Don'ts are specific to this product's failure modes, not generic platitudes.
- The file is internally consistent: every `{reference}` resolves, no duplicate sections, contrast claims hold.
