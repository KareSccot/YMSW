# Judgment Framework

Use this reference for substantial UI audits, screenshot reviews, before/after comparisons, or design direction work. For a small component, use only the relevant sections.

## Evidence standard

Every finding should connect four parts:

```text
observable decision → encoded meaning → user effect → recommended change
```

Weak:

> The page feels busy.

Strong:

> Four equally saturated cards compete with the error summary, so the user cannot identify the blocking issue. Keep the error summary as the only high-contrast region and demote healthy metrics to compact text.

Avoid claiming intent you cannot observe. Say “this communicates…” rather than “the designer wanted…”.

## Pass 1: Reconstruct the interface contract

Identify:

- user and current context;
- primary outcome;
- information needed to decide;
- primary and secondary actions;
- system state and uncertainty;
- consequences and recovery;
- target frequency, density, and expertise.

If reviewing a screenshot, distinguish what is visible from what is inferred.

### Screen-level information map

Write a compact map:

```text
Primary
  outcome / blocking state / main decision

Supporting
  evidence / comparison / explanation

Reference
  metadata / history / optional detail

Actions
  primary / secondary / destructive / reversible
```

Escalate to `product-design` when the problem requires new domain concepts, product modules, global navigation, or multi-page journey redesign.

## Pass 2: Foundation gates

A gate is pass, partial, fail, or unknown. Unknown is not a pass.

### True

- Data source and freshness appear where consequential.
- Generated or inferred content is distinguishable from confirmed facts.
- Uncertainty is expressed proportionally.
- Labels and previews match actual outcomes.

Hard failures:

- fabricated certainty;
- hidden scope or irreversible consequence;
- misleading visual emphasis.

### Operable

- Interactive elements are discoverable.
- Current, hover, focus, selected, loading, success, and failure states are intelligible.
- The user can complete the primary task without guessing.
- Errors explain recovery.

Hard failures:

- action with no visible response;
- destructive action presented as routine;
- status visible only after the user leaves context.

### Inclusive

- Logical keyboard and reading order.
- Sufficient contrast and non-color state cues.
- Zoom and narrow widths preserve meaning.
- Motion respects reduced-motion preference.
- Touch targets and labels remain usable.

Hard failures:

- visual order contradicts DOM order;
- essential meaning encoded only by color;
- focus removed without replacement.

### Controllable

- Consequential actions can be reviewed, cancelled, undone, or corrected.
- Automation exposes scope and result.
- Defaults do not silently remove agency.
- The interface keeps the user oriented during background work.

Hard failures:

- irreversible action without informed confirmation;
- automation with no way to inspect or correct output.

Fix gate failures before visual polish.

## Pass 3: Information and action structure

### Priority

- Is there one primary anchor?
- Does visual weight correspond to importance now?
- Are blocking states stronger than healthy background state?
- Are actions visually proportional to consequence?

### Grouping

- Does proximity match semantic belonging?
- Are unrelated items separated without unnecessary containers?
- Are labels attached to the values or controls they explain?
- Can repeated groups be scanned by a stable alignment line?

### Sequence

- Does the reading order follow the decision process?
- Is prerequisite information encountered before action?
- Does the interface reveal the result where the action occurred?
- Are multi-step tasks oriented by progress and completion criteria?

### Disclosure

- Is essential context visible without interaction?
- Are advanced or rare controls available without dominating?
- Is complexity revealed by user need rather than arbitrary page breaks?
- Does hiding detail create dangerous ambiguity?

### State and consequence

- Can users answer “where am I,” “what changed,” and “what happens next”?
- Are pending and stale states distinct?
- Are empty, zero, unavailable, and error states distinguished?
- Does recovery preserve user work?

## Pass 4: Reading hierarchy

Inspect both page-level and repeated-unit reading order.

- What is the page's macro anchor?
- What is the micro anchor in each row, card, result, or form group?
- Can section labels be scanned as structural signposts?
- Are item identity, supporting description, reference metadata, state, and action perceptually distinct?
- Does the spacing cadence express “same thought → pause → new group”?
- Does the hierarchy survive inactive, selected, warning, long-label, and wrapped states?

Hard failures:

- primary identifier and supporting description share effectively the same visual role;
- section labels disappear even though grouping depends on them;
- several near-identical gray levels create a flat text texture;
- state styling makes an item identity difficult to find;
- typography is technically legible but cannot be scanned by role.

For detailed diagnosis, read [`reading-hierarchy.md`](reading-hierarchy.md).

## Pass 5: Maturity evidence

Assign the highest level with sufficient evidence. Do not average levels.

### Level 1 — Usable

Evidence:

- primary task completes;
- required information exists;
- navigation and recovery are understandable.

### Level 2 — Comfortable

Evidence:

- reading and action rhythm reduce effort;
- density fits frequency and expertise;
- feedback is timely and calm;
- spacing and type create reliable scan paths.

### Level 3 — Language

Evidence:

- semantic roles repeat through consistent form and behavior;
- component states are complete;
- exceptions are purposeful;
- pages feel governed by shared decisions.

### Level 4 — Taste / Personality

Evidence:

- personality follows task and audience;
- character persists without logo, illustration, or a single effect;
- restraint and emphasis form a recognizable point of view.

### Level 5 — Culture

Evidence:

- defaults and workflows embody stable values;
- tone, control, and lifecycle reinforce the same worldview;
- the design influences behavior beyond a single screen.

## Pass 6: Five-force diagnosis

Find the weakest force first.

### Information structure

Symptoms:

- competing anchors;
- card grid used as default information architecture;
- actions separated from decision evidence;
- visual placement contradicts semantic order.

### Rhythm

Symptoms:

- every gap is equal regardless of relationship;
- too many type sizes or weights;
- no pause between distinct sections;
- repeated motion has inconsistent timing.

### Consistency

Symptoms:

- same state uses different color or labels;
- component variants encode taste rather than meaning;
- page-local exceptions multiply;
- the same action moves unpredictably.

### Restraint

Symptoms:

- decorative containers around every group;
- multiple strong accents;
- icons repeat text without adding recognition;
- empty space is filled merely because it is empty.

### Surprise

Symptoms:

- no memorable detail when personality is a valid goal;
- delight placed on high-frequency or high-risk actions;
- motion and effects compete with status;
- novelty breaks learned behavior.

Surprise is optional. A stable, quiet tool is not incomplete because it lacks spectacle.

## Pass 7: Design dimensions

Judge dimensions by what they communicate.

### Typography

- hierarchy, voice, line length, density, numerals, truncation;
- weight should not substitute for structural grouping.

### Color

- semantic role, interaction, state, contrast, accent scarcity;
- color should not be the sole state channel.

### Space and alignment

- semantic distance, scan lines, density, edge relationships;
- whitespace is active only when it clarifies grouping or emphasis.

### Shape and depth

- affordance, containment, hierarchy, material metaphor;
- a card, border, radius, or shadow needs a role.

### Motion

- causality, continuity, feedback, tempo, reduced-motion fallback;
- motion should explain change before it entertains.

### Copy and iconography

- directness, warmth, vocabulary, recognition, consequence;
- icons supplement labels when ambiguity or stakes are high.

## Pass 8: Subtraction review

For each strong visual element, ask:

1. What information or affordance does it carry?
2. Would removing it reduce comprehension or control?
3. Can hierarchy, proximity, or copy do the job with less noise?
4. Is the element part of the language or a page-local flourish?

Remove arbitrary difference, not domain complexity.

## Paired comparison

When comparing variants, hold the task and content constant. Evaluate:

1. Which variant reveals the primary outcome faster?
2. Which preserves clearer semantic grouping?
3. Which better connects evidence, action, and consequence?
4. Which uses fewer unrelated visual rules?
5. Which personality better fits the premises?
6. Which remains more robust across states and widths?

Name the winner only after citing the decisive differences. A tie is valid when trade-offs depend on missing context.

## Detailed critique template

```markdown
# Design review

## Verdict
Current maturity: Level N — <name>
Target maturity: Level M — <name>
Weakest force:
Evidence:
Ladder (cumulative, always list all five):
1. Usable — the correct task can be completed.
2. Comfortable — reading, motion, spacing, and feedback reduce effort.
3. Language — the same meaning repeatedly produces the same form and behavior.
4. Taste / Personality — decisions express a recognizable character without a logo.
5. Culture — defaults and interactions embody a durable worldview.

## Premises
- User and outcome:
- Task topology:
- Frequency and risk:
- Intended personality:
- Unknowns:

## Foundation gates
- True:
- Operable:
- Inclusive:
- Controllable:

## Information and action map
- Primary:
- Supporting:
- Reference:
- Actions and consequences:

## Findings by impact
1. [Finding]
   - Evidence:
   - User effect:
   - Change:

## Design direction
- Preserve:
- Remove:
- Strengthen:
- Semantic language:
- Surprise budget:

## Implementation handoff
- First change:
- State coverage:
- Relevant design-system skill:
```

## Anti-reskin test

Before finalizing a redesign, compare old and new:

- Did priority or sequence improve?
- Did an execution or evaluation gulf narrow?
- Did state, consequence, or recovery become clearer?
- Did the design language become more semantic?

If only colors, radii, shadows, and spacing changed, describe it honestly as visual refinement rather than structural redesign.
