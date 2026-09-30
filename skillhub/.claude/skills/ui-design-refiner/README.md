# UI Design Refiner

Framework-agnostic guidance for judging, verifying, and refining an existing product interface — raising it up the design maturity ladder (Level 1 to Level 5) by clarifying its information structure, coherent interaction language, and restrained visual character. It matures surfaces that already exist rather than generating them from a blank page.

## What it does

- Reviews UI quality with evidence instead of vague “looks good” judgments.
- Verifies what is broken and diagnoses what limits the design's maturity.
- Refines screen composition against user intent, information priority, and action consequences.
- Uses rhythm, consistency, restraint, and bounded surprise to strengthen a recognizable product personality.
- Helps implement focused frontend refinements while respecting the existing design system.
- Uses small code atoms only when prose cannot communicate a design distinction.
- Runs an end-to-end **Optimize** workflow for whole-surface upgrades: a design-evaluation report, a coarse-to-fine upgrade loop (max 4 passes), and a portable `DESIGN.md` design-system export.

## When to use it

Use `ui-design-refiner` for requests such as:

- “Make this dashboard feel less generic.”
- “Review the visual hierarchy of this settings page.”
- “Give this product a more intentional personality.”
- “Refine this React component so it feels polished.”
- “The UI works, but it has no design sense.”

It is not the tool for generating a UI from nothing; get a first draft elsewhere, then bring it here to judge and refine.

## Relationship to other skills

- `product-design` defines domain concepts, product modules, pages, and journeys.
- `ui-design-refiner` judges, verifies, and refines screen-level information, interaction, composition, and taste.
- `gdt-ui-design` and `raven-ui-design` apply named-system tokens and components.
- `ui-semantic-class-names` preserves business meaning in frontend markup.
- `canvas-design` is for static visual art rather than product interfaces.

## Structure

```text
ui-design-refiner/
├── skill.json
├── SKILL.md
├── README.md
├── references/
│   ├── design-foundations.md
│   ├── judgment-framework.md
│   ├── reading-hierarchy.md
│   ├── atomic-code-patterns.md
│   ├── optimize-workflow.md
│   └── design-md-spec.md
└── evals/
    └── evals.json
```

## Design stance

Software design mediates information and action. The visual layer should make priority, relationship, state, consequence, and personality perceptible. Decoration cannot rescue a broken task model.

The skill treats code as executable evidence, not as a page template or a style to imitate.

## Risk profile

All risk flags are `false`. This is a pure-prompt skill with no scripts, external calls, or dependencies.
