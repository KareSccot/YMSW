---
name: ui-semantic-class-names
description: Add business-semantic class names to UI elements when building or editing React/frontend pages, components, layouts, or JSX/TSX markup — especially in Tailwind-heavy codebases where utility classes describe how things look but not what they are. Use this skill whenever the user creates, modifies, or reviews page markup, layout, or components, even if they don't explicitly ask about "semantic classes" or "naming." It restores the layout/business meaning that pure Tailwind utility strings erase, so pages become easier to locate, read, and maintain. Complements `gdt-ui-design` (which owns the visual system) without overlapping it.
---

# UI Semantic Class Names

This skill restores the *meaning* that Tailwind utility classes strip out of your markup. Utility classes are excellent at describing **how** an element looks (`flex gap-4 p-6 rounded-lg`) and silent about **what** it is. When every container is just a string of utilities, the JSX no longer tells you the business structure of the page — you can't grep for "the order summary region," you can't skim a tree to understand its regions, and handoff/refactoring/debugging all degrade into archaeology.

The fix is small and cheap: alongside Tailwind utilities, give every meaningful element a **semantic class name** — a style-independent label that names the element's business role.

## The practice

For any element that carries business or layout meaning, put a semantic class name in the **first position** of `className`, followed by the Tailwind utilities:

```tsx
<section className="order-summary flex gap-4 p-6 rounded-lg border">
```

The semantic name is the **what** ("this is the order summary"); the utilities are the **how** ("it's a flex row with padding"). Putting the semantic name first means a reader scanning markup sees the business meaning before the styling noise.

The semantic name carries **meaning only, not styling**. By default you write no CSS for it — it's a pure identity anchor. Because it's decoupled from appearance, restyling the element never invalidates the name. Keep using the project's existing class-composition tools (`twMerge`, `cva`, etc.) for the utility portion, but always keep the semantic name at the front of the resulting string.

## Naming rules (region-prefixed / BEM-lite)

Use kebab-case, all lowercase. Name elements by **role and purpose**, never by appearance or position.

- Use full, readable words that reflect a domain or business concept:
  `order-summary`, `patient-header`, `approval-timeline`, `filter-bar`, `empty-state`, `page-shell`, `sidebar-nav`, `content-area`, `page-header`, `primary-action`, `stat-card`.
- Express hierarchy with a region prefix. The parent owns the base name; children reuse it as a prefix so belonging is obvious at a glance:
  - parent: `order-summary`
  - children: `order-summary-id`, `order-summary-label`, `order-summary-value`, `order-summary-actions`
- The child suffix still names the **role**, not the appearance — `order-summary-actions` (what it is), not `order-summary-flex-row` (how it looks).

### What to avoid

- **No generic / positional / numbered names** — `wrapper1`, `container2`, `div3`, `inner`, `outer`, `holder`. They carry no meaning; they proliferate and rot.
- **No abbreviations** — `npp`, `usr`, `cnt`, `cfg`, `hdr`. Spell the concept out: `new-patient-panel`, `user-header`, `content-area`. If a teammate has to guess what the letters mean, the name has failed.
- **No style-leaking names** — `blue-box`, `left-col`, `mt-4-section`, `rounded-card`. These bake appearance into the label, so the moment you restyle (change color, switch to a grid, remove the margin), the name lies. Semantics must survive a redesign.

## Where to apply

Apply a semantic name to elements that have a **business or layout role**:

- Page regions and shells: `page-shell`, `sidebar-nav`, `topbar`, `content-area`, `page-header`, `page-footer`
- Sections and cards: `order-summary`, `patient-header`, `stat-card`, `approval-timeline`
- Lists and items: `result-list`, `result-item`, `filter-bar`, `action-bar`
- Form structure: `login-form`, `billing-form-group`
- States: `empty-state`, `loading-skeleton`, `error-banner`

Don't apply them to every leaf text node or trivial `<span>` — that just adds noise. Label the elements whose role matters for understanding the page's structure. A `<span>` that only bolds a word inside `order-summary-value` does not need its own semantic name.

## Before / After

Before — pure Tailwind, no meaning. Reading this tells you nothing about the business; you'd have to read the text content to guess what it is:

```tsx
<div className="flex gap-4 p-6 rounded-lg border">
  <div className="flex flex-col gap-2">
    <span className="text-sm text-gray-500">订单号</span>
    <span className="text-lg font-semibold">#10245</span>
  </div>
  <div className="ml-auto flex gap-2">
    <button className="px-3 py-1 rounded bg-blue-600 text-white">导出</button>
  </div>
</div>
```

After — semantic name first on every meaningful element, utilities follow, hierarchy expressed via prefixes:

```tsx
<section className="order-summary flex gap-4 p-6 rounded-lg border">
  <div className="order-summary-id flex flex-col gap-2">
    <span className="order-summary-label text-sm text-gray-500">订单号</span>
    <span className="order-summary-value text-lg font-semibold">#10245</span>
  </div>
  <div className="order-summary-actions ml-auto flex gap-2">
    <button className="export-order-button px-3 py-1 rounded bg-blue-600 text-white">导出</button>
  </div>
</section>
```

Every `className` above opens with the semantic name; the utilities describe the rest. A reader now sees "order summary → its id → label + value → actions → export button" without reading a single Chinese character.

## Anti-patterns at a glance

| Avoid | Prefer | Why |
|------|--------|-----|
| `wrapper1` / `container2` / `div3` | `order-summary` / `filter-bar` | Generic names carry no meaning |
| `npp` / `usr` / `cnt` | `new-patient-panel` / `user-header` / `content-area` | Abbreviations are unreadable |
| `blue-box` / `left-col` / `mt-4-section` | `stat-card` / `sidebar-nav` / `page-header` | Style/position leaks into semantics, breaks on redesign |
| semantic name after utilities (`flex ... order-summary`) | semantic name first (`order-summary flex ...`) | First position is what the eye lands on |

## Workflow

- **New markup**: apply as you write — semantic name first, utilities after.
- **Existing markup**: when you touch an element that lacks a semantic name, add one in the same change. Incremental cleanup keeps the cost low and the habit sticky.
- **Refactoring styles**: keep the semantic name stable; swap the utilities freely. The name is the stable anchor across restyles.
- **Alongside `gdt-ui-design`**: that skill owns the visual system (color tokens, Mantine theme, crafted components). This skill owns the *business-semantic anchors* in markup. Use both — they don't overlap.

## Don'ts

- Don't bury the semantic name after or between utilities — always put it first, so the first thing a reader sees is the business meaning.
- Don't write CSS for the semantic class (unless the project later deliberately adopts it for styling). Its default job is to be a label, not a style hook.
- Don't use the semantic class as a test/e2e selector. Keep meaning and test selectors decoupled — otherwise a test refactor will pressure you into renaming a business label, corrupting the very semantics this skill protects. Use `data-testid` (or the project's convention) for tests.
- Don't name by appearance, position, number, or abbreviation. Name by role.
