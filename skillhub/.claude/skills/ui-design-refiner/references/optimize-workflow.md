# Optimize workflow

The end-to-end protocol for the **Optimize** mode: turn an existing interface into a materially better, coherent one, then record the result as a portable design system. Read this when a request asks to upgrade a whole surface or product rather than critique or build a single piece.

The whole point is disciplined sequencing. Left alone, redesigns tend to jump straight to color and spacing, so the structural problems survive under a nicer coat of paint. This workflow forces the opposite: prove the structure first, descend to detail only once the layer above holds, and cap the effort so it converges instead of fiddling forever.

## Stage 1 — Evaluate and report

Run a real evaluation against the user's actual goal before touching anything. Use the full [`judgment-framework.md`](judgment-framework.md): reconstruct the interface contract, check the four foundation gates, map information and action, diagnose current maturity and the weakest force, and assess responsive behavior across the declared viewport range.

Produce a written **design report** as the deliverable of this stage. Use the detailed critique template in `judgment-framework.md` (Verdict → Premises → Foundation gates → Information/action map → Findings by impact → Design direction). Three things this report must nail, because the rest of the workflow depends on them:

- **Current maturity level and a target level.** The target is the finish line for the loop. Be honest — a rigorous Level 3 tool usually should not be pushed to Level 5 storytelling.
- **Findings ranked by impact**, each tagged with the detail level it belongs to (structure / hierarchy / language / detail). This tagging is what lets the loop go coarse-to-fine instead of thrashing.
- **Responsive baseline.** Declare the viewport range that matters and record how the current design behaves at narrow, wide, and zoomed widths. Tag responsive breakages as findings at the level they belong to (a reflow that reorders meaning is structural; a cramped touch target is micro-detail) so the loop optimizes and re-verifies them rather than treating responsiveness as a one-time polish.

The report must close with the [Maturity summary block](../SKILL.md) — current and target levels plus all five rungs with one-line descriptions — so the user sees the full ladder and understands what the loop is and is not aiming for.

Share the report with the user before starting the loop when the rework is large or the goal is ambiguous. For a clearly-scoped “just make it better,” you may state the report and proceed in the same turn.

## Stage 2 — Coarse-to-fine upgrade loop (max 4 passes)

Work top-down through four detail levels. Each pass takes the current level, applies the **smallest coherent set** of changes that resolves this level's findings, then re-evaluates *only that level*. Then decide:

- **Level holds** → descend to the next detail level on the next pass.
- **Level still weak** → spend another pass at the same level.
- **Target maturity reached, or 4 passes used** → stop and go to Stage 3.

Four passes is a ceiling, not a quota. If structure and hierarchy are already sound, you might spend two passes and be done. If structure is deeply broken, you might spend two passes there and never reach micro-detail — that is the correct trade, and you note the untouched levels as remaining work.

**Responsive is a cross-cutting requirement, not a Level-4 detail.** Check it from Level 1 onward: viewport range is declared in the Stage-1 report, structure must reflow without losing its anchor or reading order, hierarchy must survive narrow columns and wrapped text, the semantic language must adapt containers and density per width, and micro-detail covers touch targets and zoom. Whenever a pass changes layout, confirm the change holds at narrow, wide, and zoomed widths and record it in that pass's log. A design that only works at one width has not reached its target maturity, regardless of how polished that width looks.

### The four detail levels

**Level 1 — Structure (information & action architecture).**
The coarsest layer: priority, grouping, sequence, disclosure, state, consequence, and both interaction gulfs. See SKILL workflow step 3 and the "Information structure" force. Fix competing anchors, buried primary actions, card-grid-by-default, and any place where visual order contradicts semantic order. Nothing below this matters if this is wrong.
*Level-evaluation:* one primary anchor exists; actions sit near their decision evidence; reading order follows the decision process; blocking state outweighs healthy background state; the structure reflows across the declared viewport range without losing the anchor or reordering meaning.

**Level 2 — Reading hierarchy.**
Correct structure can still be unreadable. Map every text role and set macro/micro anchors, coordinated size/weight/color/spacing channels, and scan-able group labels. See SKILL step 4 and [`reading-hierarchy.md`](reading-hierarchy.md).
*Level-evaluation:* run the three scans — group boundaries scan-able alone; item anchors scan-able without reading descriptions; state/action distinguishable from identity. Roles stay distinct when squinting.

**Level 3 — Semantic design language & visual expression.**
Turn repeated decisions into a language: the `semantic role → hierarchy → component → color role → behavior → motion` mapping, plus consistency and restraint. See SKILL steps 6–7 and the Consistency/Restraint forces. This is also where personality is deliberately chosen from premises, and where accent, depth, and contrast are budgeted as scarce resources.
*Level-evaluation:* the same meaning produces the same form everywhere; accents and containers earn their place; personality traces back to task/audience/risk, not a borrowed brand.

**Level 4 — Micro-detail.**
The finest layer: state coverage (hover/focus-visible/active/disabled/loading/empty/error/success), motion timing and causality, copy tone and consequence, the surprise budget, and accessibility polish. See SKILL step 8 and the Surprise force.
*Level-evaluation:* every interactive element has intelligible states; motion explains change before it entertains; surprise is scarce and placed away from high-frequency/high-risk actions; keyboard, contrast, zoom, reduced-motion, and narrow/wide responsive widths hold.

### Per-pass log

Keep the loop transparent. For each pass, report a compact block so the user can follow the reasoning and the descent:

```text
Pass N — Level: <structure|hierarchy|language|detail>
Changed: <smallest coherent set of upgrades, tied to Stage-1 findings>
Level check: <held | still weak — why>
Responsive: <how the change holds at narrow / wide / zoomed — required whenever layout moved>
Next: <descend to Level X | repeat this level | stop: target reached / cap hit>
```

If code is requested, apply changes with the project's existing primitives and keep DOM/focus order intact (SKILL step 8). If the deliverable is direction rather than code, describe each pass's changes concretely enough to implement.

### Stopping and honesty

Stop the moment the target maturity is met — do not burn remaining passes manufacturing difference. When the cap is hit with levels still open, say so plainly and list what remains, so the export and the handoff stay truthful (see the anti-reskin test in `judgment-framework.md`).

Before you stop, run a final responsive verification against the Stage-1 baseline: confirm that every responsive finding you raised is resolved, and that the changes you made did not introduce new breakage at narrow, wide, or zoomed widths. Report the result. If a responsive breakage remains, it is unfinished work — either spend a pass on it (within the cap) or list it explicitly among the remaining work, never omit it.

## Stage 3 — Ask about DESIGN.md, then record

This stage does not automatically write a file. Capturing the converged design as a `DESIGN.md` is what makes the optimize run durable — the next session or agent inherits the tokens and rationale instead of re-deriving them — but whether and where to write it is the user's call, so the stage begins with a question, not an export.

### Ask first

Do not write or overwrite `DESIGN.md` silently — a stale or duplicated design record is worse than none, and clobbering an existing one can erase decisions the team relies on. First check whether a `DESIGN.md` already exists in the project, then ask the user what to do and wait for the answer before emitting anything:

- **A `DESIGN.md` already exists** → ask whether to update the existing file in place or create a new one alongside it (and where).
- **No `DESIGN.md` exists** → ask whether to generate one now (default yes) or skip and keep the design record inline in this turn.

Ask the way the harness supports. If a structured ask-user tool is available (e.g. an AskQuestion / ask-user tool), use it so the choice is a clean selection; if none exists, print the question inline as plain text and wait for the reply. Only after the user chooses do you act on the choice below.

### Then record, per the user's choice

If the user asked to update or create the file, write it following [`design-md-spec.md`](design-md-spec.md). If they asked to skip, keep the design record inline in this turn instead. Either way:

- Populate tokens (colors, typography, rounded, spacing, components) from the final design — not an aspirational one. Every token should trace to a decision made during the loop.
- Write the body sections (Overview through Do's and Don'ts) as rationale: the Overview carries the personality and anti-personality set in Stage 1; the Do's/Don'ts encode the specific failure modes you fixed, including the responsive rules you converged on.
- If the project already has design tokens or a named design-system skill, align the DESIGN.md to that vocabulary rather than inventing a competing one; note where it defers to the system.

Close by pointing at any levels the loop did not reach, so the design record and the remaining work are both visible.
