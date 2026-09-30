# Project Agent Instructions

This repo uses **agentic tracing**: every material change is recorded and tracked so the project keeps a durable memory and does not drift as different agents pick up the work across sessions. This file is the always-on source of truth — read it before acting.

Why this exists: agents are strong within a session but forget across sessions. Without a durable record they re-decide settled questions and drift the product off course. The living snapshots + change records + changelog + decision log below are that memory. Treat them as the source of truth, not as bureaucracy.

## What to trace (materiality threshold)

Only **significant** changes go through the change flow below — global, structural, strategic, or design-level decisions with lasting consequences.

- Trace: a new module or service, a data-model change, an auth/permission model, a cross-cutting refactor, a dependency or framework switch, an API contract change, a notable UX flow.
- Do NOT trace: copy tweaks, styling nits, small bug fixes, local refactors, config bumps, comments/docs. These are ordinary commits — no change directory, no changelog row.

When in doubt, ask: "would a future agent need to know why this was done to avoid undoing or re-deciding it?" If no, skip the trace.

## Directory conventions

- `docs/PRD.md` — the **current snapshot** of product requirements (living doc, single source of truth): problem, users, scenarios, scope, non-goals.
- `docs/design.md` — the **current snapshot** of the technical architecture (living doc), including a **Decisions** section that is the durable decision log — not a separate ADR file.
- `docs/changes/changelog.md` — the index of every traced change.
- `docs/changes/{slug}/` — the full record of each traced change. Slug format is `NNN-kebab-case-name` (e.g. `000-init`, `001-add-social-login`); the number increments from the existing directories.

## What each change directory contains

| File | Stage | Content |
|---|---|---|
| `prd.md` | 1. Spec/PRD | The product requirements this change touches. `000-init` is the full set; later changes write only the delta (read the current `docs/PRD.md` first) and confirm it still fits the product's scope and intent |
| `mockup.html` | 2. Mock (optional) | Only when the change has a user-facing surface: an interactive prototype of the affected screens. Skip for backend / API / CLI / data / infra changes (leave empty or delete). Archive for this change only — do **not** merge prototypes across changes |
| `tech_design.md` | 3. Tech Design | Technical approach, data model, and key decisions with reasoning/trade-offs. `000-init` is the full set; later changes write only the delta. These decisions are what get consolidated into `docs/design.md` |
| `plan.md` | 4. Plan | Task checklist; each task names precise file paths and acceptance criteria |
| (code + commits) | 5. Execution | Implement per `plan.md` |
| `review.md` | 6. Review | Issues grouped by critical / major / minor, plus the merge decision |

## Workflow rules

1. **Context bootstrap — before starting any change**, read `docs/PRD.md`, `docs/design.md`, and `docs/changes/changelog.md` to load the intent, the standing decisions, and the history. If a change is in progress, also read its `docs/changes/{slug}/` artifacts. Do this every time; it is what keeps you from drifting or re-deciding settled questions.
2. **Only trace material changes** (see the threshold above). Skip the change flow for trivial/local work.
3. **Advance one stage at a time, and stop for the user to confirm after each stage.** Do not auto-run all stages back to back.
4. **No TDD.** Do not require writing tests before implementation; testing and quality checks happen in the Review stage.
5. **Only after Review passes**, merge this change's `prd.md` / `tech_design.md` deltas back into `docs/PRD.md` / `docs/design.md` (decisions go into the `docs/design.md` Decisions section), and update this change's status to `merged` in `docs/changes/changelog.md`. Do not touch the two snapshot files before review passes.
6. Create new change directories with `bash .cursor/skills/agentic-tracing/scripts/new-change.sh <slug> "<title>"`. Do not create them by hand; the number is computed by the script.

## Common trigger phrases

- "I want to add a feature / make a structural change: xxx" → if it clears the materiality threshold, create a new slug directory with `new-change.sh` and run the stages
- "This review passed, merge it into the main docs" → triggers rule 5 (merge back + status update)
