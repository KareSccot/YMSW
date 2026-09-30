# UI Semantic Class Names

This skill restores the business/layout meaning that Tailwind utility classes erase from markup.

## The problem

Tailwind utilities describe **how** an element looks (`flex gap-4 p-6 rounded-lg`) but not **what** it is. When every container is just a utility string, JSX stops communicating the page's business structure — you can't grep for "the order summary region," skimming the tree tells you nothing, and locating/reading/maintaining/handing off the page all get harder.

## The practice

For any element with a business or layout role, put a **semantic class name first** in `className`, followed by the Tailwind utilities:

```tsx
<section className="order-summary flex gap-4 p-6 rounded-lg border">
```

The semantic name is the *what*; the utilities are the *how*. The semantic name carries meaning only — by default you write no CSS for it, so restyling never invalidates it.

## Naming rules (region-prefixed / BEM-lite)

- kebab-case, all lowercase.
- Name by **role/purpose**, never by appearance or position.
- Full readable words, no abbreviations: `order-summary`, `patient-header`, `filter-bar`, `empty-state`.
- Express hierarchy with a prefix: parent `order-summary`, children `order-summary-id`, `order-summary-label`, `order-summary-actions`.
- Avoid: `wrapper1` / `div3` (generic), `npp` / `usr` (abbreviations), `blue-box` / `left-col` / `mt-4-section` (style/position leaks into semantics).

## Where to apply

Page regions, sections, cards, list items, action bars, form groups, empty states — elements whose role matters. Not every leaf `<span>`.

## Relationship to Tailwind and `gdt-ui-design`

- Coexists with Tailwind — semantic name first, utilities after; keep using `twMerge`/`cva` for the utility portion.
- Complements `gdt-ui-design`, which owns the visual system (color tokens, Mantine theme, crafted components). This skill owns the business-semantic anchors in markup. The two don't overlap.

## Don'ts

- Don't bury the semantic name after the utilities — keep it first.
- Don't write CSS for the semantic class by default.
- Don't use it as a test/e2e selector — keep meaning and test selectors decoupled (`data-testid` for tests).
