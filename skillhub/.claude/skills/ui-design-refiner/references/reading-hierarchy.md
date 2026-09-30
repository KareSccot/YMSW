# Reading Hierarchy

Use this reference when a design is structurally correct but still feels flat, tiring, unfocused, or difficult to scan—especially in tables, settings, logs, queues, and repeated technical rows.

## Three different qualities

Do not collapse these into “typography.”

- **Legibility** — Can an individual character or word be recognized?
- **Readability** — Can sentences and blocks be read with sustained comfort?
- **Scannability** — Can a user skip most text and still find the needed object, state, or action?

Product interfaces often fail at scannability even when every line is technically legible.

## Hierarchy exists at two scales

### Macro hierarchy

The page tells the user:

- where they are;
- what matters now;
- which region contains the main work;
- where the primary action belongs.

### Micro hierarchy

Each repeated unit tells the user:

- what object this is;
- what it means;
- what state it is in;
- what reference detail is available;
- what can be done.

A page can pass macro hierarchy and fail micro hierarchy. Grouping feature flags into sections improves the page structure, but the rows still fail if the key, description, and metadata look interchangeable.

## Start with text roles, not font values

Inventory the roles before choosing sizes or colors:

```text
page context
  section signpost
    item identity
      supporting explanation
      reference metadata
      state
      action
```

For each role, state:

1. What question does it answer?
2. Is it scanned, read, or consulted?
3. How often is it needed?
4. What should visually outrank it?
5. What should it visually outrank?

This prevents arbitrary choices such as making every label muted or every identifier bold.

## Choose the scan anchor

Every repeated unit needs one reliable entry point.

Examples:

- Feature flags: the exact key when engineers search by identifier.
- Incident queue: severity and blocking reason before the incident ID.
- Customer list: customer name before account metadata.
- Approval list: requested change before requester metadata.
- Audit log: event verb and object before timestamp.

The anchor depends on the task, not the data schema.

### Stable identity across state

Do not automatically dim disabled, off, completed, or archived rows. Ask whether users still need to locate and compare them.

State should usually be a separate semantic channel:

```text
identity stays findable
state becomes recognizable
```

Demote the identity only when the whole item is genuinely lower priority in the current task.

## Use coordinated contrast

Color alone rarely produces a robust hierarchy. Combine at least two channels.

### Size

Use size for meaningful jumps, not one-pixel distinctions that disappear at normal distance.

### Weight

Reserve stronger weight for anchors and short labels. Large areas of semibold text create a dark, monotonous page.

### Color

Create clearly separated text roles:

- strong ink for primary anchors;
- readable secondary tone for explanation;
- quieter tone for reference metadata;
- semantic colors for state, not general hierarchy.

Avoid a ramp of near-identical middle grays. It produces “gray mush”: everything is technically different but perceptually equal.

### Type family

A second family can signal a different kind of content, such as code identifiers or tabular values. It should not compensate for weak priority.

Monospace answers “what kind of content is this?” It does not answer “how important is it?”

### Form

Case, tracking, labels, rules, icons, and alignment can distinguish structural signposts from content anchors.

A section label may be smaller than row content while remaining structurally visible through weight, tracking, space, and an alignment rule.

## Compose spatial rhythm

Rhythm is the alternation of emphasis and pause.

Use relative distance:

```text
anchor ↔ explanation       close
explanation ↔ metadata     small pause
row ↔ row                  repeated cadence
group ↔ group              largest pause
```

Equal gaps imply equal relationships. If every vertical interval is similar, the interface becomes a continuous texture without sentences or paragraphs.

### Repeated rows

Keep the anchor baseline stable so the eye can scan vertically.

- Align repeated identities to one edge.
- Keep controls in a stable column.
- Let descriptions wrap predictably.
- Prevent metadata from shifting the primary anchor.
- Use group spacing to create a genuine pause, not merely a faint label.

### Dense interfaces

Density is compatible with readability when relationships are explicit.

Prefer:

- compact spacing within a known pattern;
- consistent row anatomy;
- a small number of strong alignment lines;
- quiet metadata that remains readable on demand.

Avoid using large whitespace to compensate for weak hierarchy.

## Structural signposts

Section labels, table headers, and group dividers are navigation infrastructure.

They should:

- appear before the content they classify;
- survive quick scanning;
- repeat in one stable form;
- create a pause before a new semantic group;
- remain distinct from item identities.

They do not need to be the darkest text. They do need enough contrast and surrounding space to perform their structural job.

## Technical identifiers

Uppercase underscore-separated keys are hard to read because they lack familiar word shapes.

When the exact key is the user's search target:

- keep it verbatim;
- give it the row's strongest stable text role;
- use a readable size and line height;
- allow wrapping or safe truncation with full-value access;
- consider monospace for semantic identification, not decoration;
- keep the human description clearly subordinate but readable.

When non-technical users do not need the raw key, lead with a human label and retain the identifier as metadata.

## State, action, and identity must not compete

In a feature-toggle row:

- identity answers “which flag?”;
- description answers “what does it affect?”;
- state answers “is it on?”;
- metadata answers “where and when?”;
- switch answers “what can I change?”

Do not let a green state, a bold key, a dark description, and a large switch all become equal anchors.

A useful reading order is:

```text
flag key → description → state/action → metadata on demand
```

For a risk queue, the order may instead be:

```text
blocking reason → affected object → deadline/state → action → metadata
```

## Diagnostic tests

### Squint test

Blur or squint at the interface.

- Do groups still separate?
- Does one text line lead each repeated unit?
- Are metadata blocks quieter?

If all text becomes one gray rectangle, strengthen relative contrast and pauses.

### Three-second test

After three seconds, can a user point to:

- the page purpose;
- the active group;
- one item identity;
- the current state;
- the next action?

### Role-isolation scan

Try scanning only:

1. section labels;
2. item anchors;
3. states;
4. actions.

Each should form a coherent path without requiring the description.

### Long-content test

Use:

- the longest realistic identifier;
- a two-line localized description;
- missing metadata;
- zero and error states;
- active and inactive items.

Hierarchy should survive content variation.

### Multiple-state test

Compare rows side by side:

- on and off;
- healthy and warning;
- selected and unselected;
- enabled and permission-disabled.

State styling must not erase the stable identity or introduce a second competing anchor.

## Feature-toggle contrast example

This atom shows relationships, not universal values:

```css
.flag-key {
  color: var(--text-strong);
  font-family: var(--font-mono);
  font-size: var(--text-item);
  font-weight: 600;
  line-height: 1.35;
}

.flag-description {
  color: var(--text-supporting);
  font-size: var(--text-supporting-size);
  font-weight: 400;
  margin-block-start: var(--space-tight);
}

.flag-metadata {
  color: var(--text-reference);
  font-size: var(--text-reference-size);
  margin-block-start: var(--space-pause);
}
```

The invariant is:

```text
identity is strongest
description is readable but supporting
metadata is separated by a pause and remains reference-level
```

Do not copy the atom until those are the correct roles for the current task.

## Common failures

- Key and description use the same size, weight, and color.
- Section labels are so faint that grouping disappears.
- Every line is medium gray and medium weight.
- State color demotes item identity unexpectedly.
- Bold is used everywhere to compensate for low contrast.
- Metadata sits as close to the anchor as the description does.
- Monospace is mistaken for hierarchy.
- Group spacing and row spacing are nearly equal.
- All hierarchy is encoded in color.
- Responsive wrapping changes which line appears primary.

## Handoff language

When explaining a readability change, name the reading behavior:

> Make the flag key the stable row anchor; keep the description supporting; separate metadata with a larger pause; let section labels act as visible signposts.

Do not stop at:

> Increase the key to 14px and use a darker gray.

The first statement generalizes. The second is only one possible implementation.
