# Atomic Code Patterns

Read this reference only when implementation is requested or code resolves an otherwise vague design distinction.

These snippets are **design probes**, not templates. Each isolates one relationship. Adapt semantics, primitives, values, and syntax to the current project.

## How to use an atom

1. State the design signal.
2. Name the invariant that creates it.
3. Show the smallest code that makes the invariant inspectable.
4. Compare it with the current implementation.
5. Keep only the rule that belongs in the product.

Do not combine these atoms into a page or install a framework to reproduce them.

## Information structure

### Atom 1: Semantic order carries priority

**Signal:** The user encounters outcome, evidence, then action in decision order.

**Invariant:** DOM order follows meaning; visual layout may enhance but must not reverse it.

```tsx
<section aria-labelledby="review-title">
  <header>
    <h2 id="review-title">3 records need review</h2>
    <p>Validation found missing ownership data.</p>
  </header>

  <ReviewSummary issues={issues} />
  <Button onClick={openQueue}>Review records</Button>
</section>
```

The atom does not prescribe a card or grid. It makes the information-to-action sequence explicit.

### Atom 2: Disclosure follows need

**Signal:** Essential context remains visible while rare detail stays available.

**Invariant:** Progressive disclosure hides complexity, not consequence.

```html
<p>Exports include all currently filtered records.</p>

<details>
  <summary>Format and naming options</summary>
  <!-- Rare configuration, available on demand -->
</details>

<button type="submit">Export 248 records</button>
```

The record count is not hidden because it changes the meaning of the action.

### Atom 3: Relationships exist in data before layout

**Signal:** Rendering cannot casually treat primary and reference content as peers.

**Invariant:** The content model names semantic roles.

```ts
type ReviewContent = {
  outcome: ReviewOutcome;
  evidence: EvidenceItem[];
  reference?: Metadata;
  actions: ReviewAction[];
};
```

Types do not create visual hierarchy by themselves. They make the intended hierarchy available to the rendering layer.

## Rhythm

### Atom 1: Spacing expresses semantic distance

**Signal:** Items inside a thought feel closer than separate thoughts.

**Invariant:** Use fewer gap roles, named by relationship.

```css
:root {
  --gap-inline: 0.5rem;
  --gap-group: 1rem;
  --gap-section: 2rem;
}

.field-label { margin-block-end: var(--gap-inline); }
.field-group + .field-group { margin-block-start: var(--gap-group); }
.form-section + .form-section { margin-block-start: var(--gap-section); }
```

The values are replaceable. The increasing semantic distance is the design rule.

### Atom 2: A repeated row has one text anchor

**Signal:** Users can scan identifiers without reading every description.

**Invariant:** Identity, support, and reference differ through coordinated contrast and spacing.

```css
.item-key {
  color: var(--text-strong);
  font-weight: 600;
  line-height: 1.35;
}

.item-description {
  color: var(--text-supporting);
  font-weight: 400;
  margin-block-start: var(--gap-inline);
}

.item-metadata {
  color: var(--text-reference);
  font-size: var(--text-small);
  margin-block-start: var(--gap-group);
}
```

The roles matter, not the values. Do not dim the key merely because an item is off if users still need to find off items.

### Atom 3: Type roles form a cadence

**Signal:** The eye reads title → support → data without every line competing.

**Invariant:** Type roles differ in more than arbitrary font size.

```css
.section-title {
  font-size: 1.125rem;
  font-weight: 600;
  line-height: 1.3;
}

.section-support {
  color: var(--text-muted);
  font-size: 0.875rem;
  line-height: 1.5;
  max-inline-size: 60ch;
}
```

### Atom 4: Motion cadence shows sequence

**Signal:** Related items enter as one short phrase, not four separate events.

**Invariant:** Keep the group interval smaller than the transition duration.

```css
.result {
  animation: reveal 180ms ease-out both;
}

.result:nth-child(2) { animation-delay: 35ms; }
.result:nth-child(3) { animation-delay: 70ms; }

@media (prefers-reduced-motion: reduce) {
  .result { animation: none; }
}
```

Use stagger only when order carries meaning. Repeated operational lists usually need no entrance animation.

## Consistency

### Atom 1: Tokens name roles, not appearances

**Signal:** The same semantic state reads consistently across components.

**Invariant:** Map state to a shared role before mapping it to color.

```css
:root {
  --status-danger-fg: var(--red-11);
  --status-danger-bg: var(--red-2);
}

[data-status="danger"] {
  color: var(--status-danger-fg);
  background: var(--status-danger-bg);
}
```

Avoid names such as `--bright-red-card`; they preserve a surface, not meaning.

### Atom 2: Variants encode hierarchy

**Signal:** Button appearance predicts its role.

**Invariant:** A small semantic variant set is shared across the product.

```ts
type ActionPriority = "primary" | "secondary" | "danger";

const actionClass: Record<ActionPriority, string> = {
  primary: "action action-primary",
  secondary: "action action-secondary",
  danger: "action action-danger",
};
```

Do not add `purple`, `soft-glow`, or page-specific variants when they do not represent new meaning.

### Atom 3: State uses one source of truth

**Signal:** Label, icon, and availability cannot drift into contradictory states.

**Invariant:** Derive presentation from one explicit state.

```tsx
<button
  aria-busy={status === "saving"}
  disabled={status === "saving"}
>
  {status === "saving" ? "Saving…" : "Save changes"}
</button>
```

## Restraint

### Atom 1: One action owns the strongest emphasis

**Signal:** The next step is obvious without making secondary actions disappear.

**Invariant:** Only one action in a local decision group receives primary weight.

```tsx
<div className="dialog-actions">
  <Button variant="secondary" onClick={onCancel}>Cancel</Button>
  <Button variant="primary" onClick={onConfirm}>Publish</Button>
</div>
```

If “Delete” is also present, separate it spatially or place it in the relevant destructive context rather than creating three competing primary actions.

### Atom 2: Optional content earns its place

**Signal:** Absence remains quiet; the interface does not display empty scaffolding.

**Invariant:** Render supporting information only when it changes understanding.

```tsx
<h3>{title}</h3>
{description ? <p className="supporting-copy">{description}</p> : null}
```

Do not interpret this as “hide missing required data.” Missing consequential data needs an explicit state.

### Atom 3: Accent is a budget

**Signal:** Accent color reliably means interactive emphasis.

**Invariant:** Default content remains neutral; interaction opts into accent.

```css
.summary-value { color: var(--text-strong); }

.summary-link {
  color: var(--accent-interactive);
  text-decoration-thickness: 0.08em;
  text-underline-offset: 0.18em;
}
```

## Bounded surprise

### Atom 1: Tactile feedback confirms a cause

**Signal:** A button responds like a precise control without becoming animated decoration.

**Invariant:** Feedback is small, immediate, and tied to pointer action.

```css
.command {
  transition: transform 120ms ease-out, background-color 120ms ease-out;
}

.command:hover { transform: translateY(-1px); }
.command:active { transform: translateY(0); }

@media (prefers-reduced-motion: reduce) {
  .command { transition: background-color 120ms ease-out; }
  .command:hover { transform: none; }
}
```

### Atom 2: Completion appears where work happened

**Signal:** Success feels satisfying because cause and result remain continuous.

**Invariant:** Replace the local pending state; do not add a detached celebration.

```tsx
<button aria-live="polite" disabled={status === "saving"}>
  {status === "idle" && "Save"}
  {status === "saving" && "Saving…"}
  {status === "saved" && "Saved ✓"}
</button>
```

Use a separate toast only when the result must persist after the local context changes.

### Atom 3: Personality lives in microcopy

**Signal:** Tone is recognizable without changing the task.

**Invariant:** Character remains proportional to stakes.

```ts
const emptyState = {
  title: "Nothing waiting for review",
  body: "New submissions will appear here.",
};
```

A playful product might add a restrained phrase. A clinical or destructive workflow should prefer calm precision.

## Foundation gates in code

### Truth: expose freshness

```tsx
<output>
  {formattedValue}
  <small>Updated {relativeTime(updatedAt)}</small>
</output>
```

Show freshness where stale data could change a decision, not beside every value.

### Operability: preserve recovery

```tsx
{status === "error" && (
  <div role="alert">
    <p>Changes were not saved.</p>
    <button onClick={retry}>Try again</button>
  </div>
)}
```

### Inclusivity: do not remove focus

```css
.control:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}
```

### Control: make consequence inspectable

```tsx
<Button onClick={openPreview}>
  Review and publish {selectedCount} records
</Button>
```

The preview is part of the action model, not a decorative confirmation modal.

## Contrast-pair method

When a number feels arbitrary, compare nearby variants rather than declaring a universal value:

```css
/* Variant A: related items */
.cluster-a { gap: 0.5rem; }

/* Variant B: independent sections */
.cluster-b { gap: 1.5rem; }
```

Render the same content in both, then ask which relationship each spacing communicates. Keep the relationship; discard the demonstration.

## Final guard

An atom has failed if an agent can copy its appearance without understanding:

- which information is primary;
- what action or state it supports;
- why the exception exists;
- how it adapts to the current design system.

When that happens, return to prose or construct a tighter comparison.
