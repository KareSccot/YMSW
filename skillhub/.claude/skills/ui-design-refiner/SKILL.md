---
name: ui-design-refiner
description: Judge, verify, and refine an existing product interface up the design maturity ladder (Level 1 to Level 5) — raise its information hierarchy, interaction clarity, visual rhythm, coherent design language, restraint, and personality with evidence-backed changes. This skill is strongest at maturing an interface that already exists, not at generating one from a blank page. Use whenever a user asks to review, critique, verify, or make a UI feel polished, premium, tasteful, less generic, less like AI-generated UI, or more intentionally designed; when reviewing screenshots, mockups, React/Vue/Svelte components, CSS, layouts, dashboards, forms, settings pages, or product surfaces for design quality; and when restructuring or refining frontend UI without a named design system. Also use when the request mentions visual hierarchy, information density, whitespace, typography, design taste, product personality, interaction feel, or consistency. For end-to-end upgrade requests (优化 a page or product, "make this whole surface feel designed", "level this up") run the Optimize workflow: a full design-evaluation report, a coarse-to-fine upgrade loop (max 4 passes), and a portable DESIGN.md design-system export. Also trigger when the user asks for a design system doc, design spec, or DESIGN.md. Pair it with a project-specific design-system skill when one exists.
---

# UI Design Refiner

Take an interface that already exists and move it up the maturity ladder—from Level 1 (usable) toward Level 5 (culture): judge what is working, verify what is broken, and refine it until its information, actions, and expression form one coherent system—not by adding decoration. This skill is a refiner and verifier, not a from-scratch generator; agents judge design quality reliably but generate polished UI from nothing unreliably, so its job is to mature existing surfaces.

Software design mediates both **information and action**. Users need to understand what matters, know what they can do, act with confidence, and read the result. Visual style is the expression layer of that loop.

## Core stance

AI can recognize and generate aesthetic patterns, but it does not experience taste. Ground every judgment in observable evidence:

- the user intent the decision supports;
- the information relationship it makes visible;
- the action or state it clarifies;
- the rule it keeps consistent;
- the unnecessary difference it removes.

Avoid unsupported conclusions such as “this feels premium.” Explain which relationships, proportions, repetitions, or interaction details create that impression.

Read [`references/design-foundations.md`](references/design-foundations.md) when the task asks about design philosophy, aesthetic judgment, product personality, or why a design works.

**Evaluation model.** Every judgment runs the same layered lens, in this order: evidence standard → four foundation gates → maturity ladder → five design forces → design dimensions. The Workflow interleaves these judge-checks with produce-steps for flow, but they form one coherent evaluation; see [`references/judgment-framework.md`](references/judgment-framework.md) for the full passes and templates.

## Place in the design stack

Use `ui-design-refiner` for framework-agnostic interface judgment, verification, and refinement of surfaces that already exist.

- Use `product-design` for domain concepts, bounded contexts, page inventories, and end-to-end journeys.
- Use another skill or a design-system skill to generate a first draft from a blank page; bring the result here to judge and refine it up the maturity ladder.
- Use this skill to judge, verify, and refine screen-level information structure, composition, interaction feel, visual language, and taste.
- After making generic design decisions, load `gdt-ui-design`, `raven-ui-design`, or another named design-system skill for exact tokens and components.
- Pair with `ui-semantic-class-names` when markup needs stable business-semantic anchors.

Do not overwrite a coherent existing design system with a new aesthetic. Work with its vocabulary unless the user explicitly asks to change the system.

## Operating modes

There are two atomic modes and one composite. **Judge** and **Produce** are not separate methods — they are two compressed entry points into the single shared [Workflow](#workflow) below. Each mode's short procedure is an index that tells you which Workflow steps and protocol to pull for that intent. **Judge** and **Optimize** are the primary path for this skill: it verifies and refines interfaces that already exist. **Produce** is the supporting mode — used to fill a local gap while refining, not to generate a whole surface from a blank page.

- **Judge** → Workflow steps 1–2 and 5–6 (premises, gates, maturity, forces) + the [Critique protocol](#critique-protocol). The default entry point: verify quality and diagnose what limits the design.
- **Optimize** → the flagship. It *composes* the other two: **evaluate** (Judge) → a **coarse-to-fine loop** (repeated Produce across four detail levels) → **ask about DESIGN.md, then record it**. Reach for it on whole-surface upgrades of an existing interface.
- **Produce** → Workflow steps 1, 3–4, and 7–8 (premises, structure, hierarchy, language, implementation) + the [Production protocol](#production-protocol). Supporting only: reshape or fill in a component/section during refinement. This skill is not the tool for 0-to-1 generation of a full new surface; if there is nothing to refine yet, get a first draft elsewhere and bring it back here.

Pick the mode from the request, then execute through the Workflow — do not treat the mode lists and the Workflow as two competing procedures.

**Responsive is mandatory in every mode.** Whether you are judging, producing, or optimizing, responsive behavior is a required check and must appear in the output — never an optional final polish. Real interfaces are used across a range of widths and zoom levels, and reflow that loses the anchor, breaks reading order, collapses grouping, or shrinks touch targets is a structural failure, not a cosmetic one. Declare the viewport range up front (Workflow step 1), keep it in view while shaping structure, and verify narrow, wide, and zoomed behavior before handing off. If viewport information is genuinely unavailable, state the assumption you designed against rather than skipping the check.

### Judge

Use for critique, comparison, audits, and “make this better” requests.

1. Identify the user, core task, current state, and evidence available.
2. Reconstruct the information and action map before commenting on styling.
3. Check the four foundation gates.
4. Diagnose the current maturity level and weakest design force.
5. Verify responsive behavior across narrow, wide, and zoomed widths — this check is mandatory, and any reflow that breaks the anchor, reading order, grouping, or touch targets is a finding.
6. Rank findings by impact on comprehension and action.
7. Recommend the smallest coherent set of changes.
8. Show atomic implementation only where prose cannot communicate the distinction.

Use the framework silently. Report only checks that change the verdict or recommendation; do not recite every gate and force for a small request. The one exception is the [Maturity summary](#maturity-summary): every Judge output must close with it, because the user needs to see where the design sits on the whole ladder, not just the single level you diagnosed.

### Produce

Supporting mode. Use to reshape or fill in a component, section, or layout while refining an existing surface — not to generate a whole new product screen from scratch. If there is no interface to refine yet, obtain a first draft elsewhere, then judge and refine it here.

1. Derive the information skeleton and action flow.
2. State the intended personality and its opposite.
3. Choose one visual anchor and establish reading rhythm.
4. Define a small semantic grammar that can repeat.
5. Implement with the project's existing primitives.
6. Run subtraction, state, and accessibility passes, plus a mandatory responsive pass — confirm the anchor, reading order, grouping, and touch targets survive narrow, wide, and zoomed widths, and report it.

### Optimize

Use for end-to-end upgrade requests — “优化这个页面/产品”, “make this whole surface feel designed”, “level this up and give me the design system”, or any request that couples a real critique with a substantial rework of an existing interface. This is the high-level mode: it runs an evaluation, drives a coarse-to-fine upgrade loop, and ends with a portable design record. Follow [`references/optimize-workflow.md`](references/optimize-workflow.md) for the full protocol.

The shape is always the same:

1. **Evaluate → report.** Run the judgment framework against the user's actual goal and produce a written design report (verdict, information/action map, findings by impact, a responsive baseline across the declared viewport range, and a direction). This report is the contract for everything that follows — the loop optimizes against *these* findings, not against unstated taste.
2. **Coarse-to-fine upgrade loop (cap 4 passes).** Fix the design one detail level at a time, from structure down to micro-detail. After each change, re-evaluate *that level only* and re-verify responsive behavior whenever the change moved layout. If the level holds, descend to the next detail level; if it doesn't, optimize again at the same level. Stop when the target maturity is reached or after four passes — whichever comes first, and only once the responsive findings are resolved or explicitly listed as remaining work. Working coarse-to-fine matters because a beautiful button on a broken information structure is wasted work, and re-polishing details after a structural change is wasted twice.
3. **Ask about DESIGN.md, then record.** Do not export automatically. First check whether a `DESIGN.md` already exists, then ask the user whether to update the existing one, create a new one, or (when none exists) generate one at all — using the harness's structured ask-user tool when one is available, otherwise printing the question inline and waiting. Only after they choose do you capture the converged design following [`references/design-md-spec.md`](references/design-md-spec.md); if they skip, keep the record inline. Never overwrite an existing `DESIGN.md` without confirmation. See [`references/optimize-workflow.md`](references/optimize-workflow.md).

Scale the ceremony to the request. A single component asking to be “made nicer” is Judge or Produce, not a four-pass optimize run. Reserve Optimize for surfaces where the structure, hierarchy, language, and details all have room to move.

## Workflow

These eight steps are the shared toolbox behind every mode. Steps 1 and 5 (premises, maturity) frame the evaluation; the rest cluster into the four **detail levels** the Optimize loop descends through, coarse to fine:

```text
Structure          → step 3 (information & action structure)
Reading hierarchy  → step 4
Semantic language  → steps 6–7 (design forces, semantic language)
Micro-detail       → step 8 (implementation, states, motion)
```

Judge reads mostly at the top of this list; Produce runs it top to bottom; Optimize walks the levels one at a time with a re-check after each. See [`references/optimize-workflow.md`](references/optimize-workflow.md).

### 1. Declare the premises

Before choosing pixels, write down:

- **User and core outcome** — who is here, and what should be understood or completed?
- **Task topology** — linear completion, branching choice, monitoring, exploration, comparison, or multi-center work?
- **Information objects** — what entities and relationships must the user perceive?
- **Action model** — what can change, what is reversible, and what is high risk?
- **Frequency and density** — occasional setup, repeated production work, or continuous monitoring?
- **Viewport range** — which widths and devices matter (mobile, tablet, desktop, embedded), and which is the primary target? Responsive behavior is a structural constraint, not a final polish step, so declare it before choosing layout.
- **Personality tension** — choose two or three useful axes such as rigorous ↔ warm, quiet ↔ expressive, familiar ↔ exploratory.
- **Anti-personality** — state what the product must not feel like.

If these premises are missing and materially affect the result, ask one focused question. Otherwise infer sensible defaults and label them.

### 2. Pass the four foundation gates

Polish cannot compensate for a broken user relationship.

1. **True** — Are source, uncertainty, freshness, and action boundaries visible where they matter?
2. **Operable** — Can users discover actions, understand system status, complete the task, and recover from errors?
3. **Inclusive** — Does the interface work with keyboard, zoom, reduced motion, responsive widths, sufficient contrast, and non-color cues?
4. **Controllable** — Can users review, undo, cancel, correct, or override consequential behavior?

When a gate fails, fix it before aesthetic refinement.

For critique, surface failed, partial, or materially unknown gates. Do not add a four-gate status section when all gates are irrelevant to the supplied evidence.

### 3. Build the information and action structure

Structure is not a twelve-column grid. It is the explicit relationship between meaning, attention, and action.

Define:

- **Priority** — what must be noticed now, what supports it, and what is reference-only?
- **Grouping** — what belongs together, and what must remain separate?
- **Sequence** — in what order should the user read, decide, and act?
- **Disclosure** — what is always visible, available on demand, or deferred?
- **State** — what changed, what is selected, what is pending, and what failed?
- **Consequence** — what will happen next, especially for destructive or costly actions?

Use semantic order and interaction state as the source of truth. CSS placement must not create a misleading reading order.

Check both gulfs:

- **Execution gulf** — can the user translate intent into an available action?
- **Evaluation gulf** — can the user interpret state, result, risk, and next step?

Do not call a reskin a redesign when the task path or information relationships remain broken.

### 4. Compose the reading hierarchy

Correct information structure can still be hard to read. Before choosing color effects or container styling, map every visible text role:

- page title and current context;
- section or group label;
- repeated-item identity;
- supporting description;
- reference metadata;
- state and action labels.

Choose one **macro anchor** for the view and one **micro anchor** inside each repeated unit. In a technical list, the item key may be the micro anchor; in an approval queue, the blocking reason may matter more than the record ID.

Build a deliberate reading sequence:

```text
structural signpost → item anchor → supporting meaning → reference detail → action
```

- Make primary, supporting, and reference text visibly distinct through at least two coordinated channels: size, weight, color, spacing, alignment, line height, or type family.
- Keep section labels quiet but structurally legible. A smaller label can still carry weight, spacing, tracking, or a rule; it should not disappear when grouping depends on it.
- Use tight spacing inside one thought, a clear pause before metadata, and the largest pause between independent groups.
- Avoid several near-identical middle grays. If text roles blur when squinting, the hierarchy is too weak.
- Preserve the item identity as a stable scan anchor across states. Do not dim an “off” or inactive item so far that users cannot find it unless inactive items are genuinely lower priority.
- Verify long labels, uppercase identifiers, wrapping, localization, and dense rows. Monospace can identify code, but it does not create hierarchy by itself.

Run three quick scans:

1. Can the user scan only group boundaries?
2. Can the user scan only item anchors without reading descriptions?
3. Can the user find state and action without confusing them with identity?

Read [`references/reading-hierarchy.md`](references/reading-hierarchy.md) when typography, text-heavy lists, tables, settings, or “the page has no focus/rhythm” is central to the task.

### 5. Choose the appropriate maturity target

Use the ladder as a cumulative diagnostic lens, not a status badge:

1. **Usable** — the correct task can be completed.
2. **Comfortable** — reading, motion, spacing, and feedback reduce effort.
3. **Language** — the same meaning repeatedly produces the same form and behavior.
4. **Taste / Personality** — decisions express a recognizable character without relying on a logo.
5. **Culture** — defaults and interactions embody a durable worldview.

State the current level, target level, and evidence. A coherent Level 3 enterprise tool is often better than forced Level 5 storytelling.

### 6. Apply the five design forces

Treat these as interdependent checks, not a literal equation.

#### Information structure

- Create one primary visual anchor per view.
- Let grouping and semantic distance establish hierarchy.
- Keep primary action near the information needed to decide.
- Preserve necessary density; remove confusion, not useful information.

#### Rhythm

- Alternate emphasis and quiet: strong → supporting → pause → continuation.
- Use spacing to express relationship, not to make everything airy.
- Use type size, weight, line length, and line height as a coordinated scale.
- Make motion timing reflect cause, distance, and importance.

#### Consistency

- Map the same semantic role to the same appearance and behavior.
- Repeat decisions until they become a language.
- Allow exceptions only when meaning changes; document the reason.
- Reuse existing primitives before inventing new ones.

#### Restraint

- Remove competing anchors, redundant labels, decorative containers, and unearned emphasis.
- Use accent color, strong contrast, depth, and motion as scarce resources.
- Prefer precise proportion and state feedback over gradients, shadows, and icon piles.
- Keep quiet zones quiet.

#### Surprise

- Add surprise only after comprehension and operation are stable.
- Spend it where it reinforces meaning: a revealing transition, a memorable empty state, a tactile confirmation.
- Reduce it in high-risk or high-frequency workflows.
- If every element asks for attention, there is no surprise.

### 7. Establish a semantic design language

Define a compact mapping from meaning to expression:

```text
semantic role → hierarchy → component/shape → color role → behavior → motion
```

For example, do not merely standardize all cards to one radius. Define when content deserves a container, which containers are interactive, and how an interactive container reveals itself.

Personality comes from the repeated selection of constraints across:

- information density and disclosure;
- typography and editorial tone;
- geometric softness or precision;
- color temperature and accent budget;
- motion weight and tempo;
- directness, warmth, and tolerance in copy.

Do not imitate the surfaces of Apple, Linear, Notion, or another brand. Derive personality from the product's task, audience, risk, and values.

### 8. Implement from structure outward

When code is requested:

1. Preserve semantic DOM and logical focus order.
2. Reuse project tokens and components.
3. Encode recurring decisions as semantic tokens, variants, or primitives.
4. Implement default, hover, focus-visible, active, disabled, loading, empty, error, and success states as applicable.
5. Verify narrow, wide, zoomed, keyboard, and reduced-motion behavior — the responsive verification is mandatory, not conditional, and reflow must preserve the anchor, reading order, grouping, and touch targets.
6. Test zero, one, many, missing, and conflicting values when those states change the message.
7. Check repeated component instances for unique IDs and isolated state.
8. Keep the change within the requested scope.

Read [`references/atomic-code-patterns.md`](references/atomic-code-patterns.md) only when code can clarify a design distinction or the user asks for implementation.

## Code is evidence, not a style lesson

Use code to make an otherwise tacit relationship observable.

- Show one design variable per snippet.
- Explain the signal before the mechanism.
- Prefer a contrast pair or a 10–30 line atom over a complete page.
- Extract the invariant; adapt syntax and values to the current project.
- Never combine bundled snippets into a showcase page.
- Do not introduce a framework just to demonstrate a principle.

The examples are deliberately incomplete. They are not templates and should not become a visual vocabulary through copying.

## Critique protocol

When reviewing an interface, lead with the highest-impact finding rather than walking through every pixel.

Use this shape when the user has not requested another format:

```markdown
## Verdict
[One evidence-backed sentence: what level it reaches and what limits it.]

## Information and action map
- Primary outcome:
- Primary anchor:
- Supporting information:
- Main action and consequence:
- Responsive behavior (required): [how structure, anchor, reading order, grouping, and touch targets hold at narrow / wide / zoomed widths]

## Findings
1. [Problem] — [evidence] — [effect on user] — [level: structure/hierarchy/language/detail]

## Design direction
- Preserve:
- Remove:
- Strengthen:
- Personality:

## Atomic implementation
[Only if needed; one principle per snippet.]

## Maturity summary
[Required — see below.]
```

For detailed audits and paired comparisons, use [`references/judgment-framework.md`](references/judgment-framework.md).

### Maturity summary

Judge and Optimize outputs must always close with this block. Produce does not require it. Showing the whole ladder — not just the diagnosed level — lets the user see the current position, the realistic target, and what each rung would take:

```markdown
## Maturity summary
Current: Level N — <name>. Target: Level M — <name>.
Ladder (cumulative):
1. Usable — the correct task can be completed.
2. Comfortable — reading, motion, spacing, and feedback reduce effort.
3. Language — the same meaning repeatedly produces the same form and behavior.
4. Taste / Personality — decisions express a recognizable character without a logo.
5. Culture — defaults and interactions embody a durable worldview.
Why: <one line of evidence for the current level and the gap to target>.
```

Always list all five rungs even when the design sits at Level 1, so the path forward stays visible. Set the target from the premises, not ambition — a rigorous Level 3 tool usually should not be pushed to Level 5.

## Production protocol

For a new or revised interface, communicate the subset that materially affects the decision:

1. premises and assumptions;
2. information/action structure;
3. visual anchor and rhythm;
4. semantic language rules;
5. personality and anti-personality;
6. implemented scope and state coverage;
7. unresolved risks.

Scale the explanation to the task. Prefer three decisive findings over a complete framework recital. A button refinement does not need a manifesto.

## Anti-patterns

Reject these common shortcuts:

- treating decoration as design quality;
- starting with a dashboard grid before identifying the user's task;
- wrapping every information group in a card;
- using brand color on non-interactive content without semantic reason;
- adding gradients, glass, shadows, or motion to manufacture “premium” feel;
- using excessive whitespace to hide weak grouping;
- calling sparse interfaces “minimal” after removing needed context;
- copying a fashionable product's surface treatment;
- presenting arbitrary token values as universal laws;
- producing a full-page demo that teaches imitation instead of judgment.

## Final check

Before handing off, ask:

- Can a user identify the purpose and primary action quickly?
- Does semantic order match visual order?
- Does the layout hold at narrow, wide, and zoomed widths — does reflow preserve the anchor, reading order, grouping, and touch targets rather than just shrinking?
- Are status, consequence, and recovery visible?
- Is there one clear anchor?
- Does each repeated unit have one clear text anchor?
- Can group labels, item identities, descriptions, and metadata be scanned as distinct roles?
- Do spacing and typography reveal relationships?
- Does repeated meaning use repeated form and behavior?
- What can be removed without losing meaning?
- Is personality derived from premises?
- Is surprise scarce and justified?
- Did code stay atomic unless implementation required more?
