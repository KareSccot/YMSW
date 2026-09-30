---
name: agentic-tracing
description: Use ONLY when the user explicitly asks to set up / install "agentic tracing" (or an agent-driven SDLC tracing / change-record / decision-log system) in a repo. This is an explicit, opt-in setup skill — do NOT trigger it implicitly. Even if the user merely mentions PRDs, docs structure, or that their AI agents keep forgetting decisions or drifting, do not run it unless they explicitly request this setup (you may suggest it and wait). It performs a one-time scaffold of a durable tracing spine (docs/changes + living PRD/design snapshots + changelog + a decisions log) plus a root AGENTS.md and non-blocking hooks that then trace material changes automatically thereafter. It does not gather requirements or author change content, and it does not create any change — those are separate flows.
---

# Agentic Tracing

## What this skill does

Software is increasingly built by autonomous agents rather than humans typing every line. Agents are powerful within a session but forget across sessions: they lose the original intent, re-litigate settled decisions, and slowly drift the product off course. **Agentic tracing** gives a repo a durable spine that records and tracks the important changes an agentic SDLC produces, so every agent that picks up the work reads the same source of truth before acting.

This skill is the **one-time installer** for that spine. Its job is narrow: scaffold the structure, the living docs, the root `AGENTS.md`, and the hooks. After that, tracing runs on its own — this skill does not need to be invoked again.

### Two layers

- **Layer 1 — setup (explicit, one-time):** this skill. It runs only when the user explicitly asks to set up tracing, and it just scaffolds. It does not gather requirements, author change content, or create a change.
- **Layer 2 — tracing (automatic, ongoing):** the artifacts it installs. The repo-root `AGENTS.md` (which Cursor auto-includes and Claude Code / Codex read by convention) plus the non-blocking hooks make tracing happen automatically for the rest of the lifecycle — init and every iteration. Any other skill working in the repo passively obeys `AGENTS.md`. There is intentionally **no `.cursor/rules/*.mdc`**: it would just duplicate `AGENTS.md` and add a second file to keep in sync.

### What it installs

- `docs/PRD.md` — living product snapshot template (problem, users, scenarios, scope, non-goals). Starts empty.
- `docs/design.md` — living architecture snapshot template, including a **Decisions** section that is the durable decision log. There is no separate ADR store. Starts empty.
- `docs/changes/changelog.md` — the memory index (one row per traced change). Starts empty (header only).
- `docs/changes/` — the (empty) home for future change records. No change is created by setup.
- `AGENTS.md` (repo root) — the single always-on instruction doc: the materiality threshold, the context-bootstrap read, the stages, and the merge-back rule.
- `.cursor/hooks.json` + `.cursor/hooks/check-change-artifacts.sh` — a non-blocking reminder hook that nudges when a change directory has content but no matching `changelog.md` row.
- `scripts/new-change.sh` (shipped with the skill) — the helper the ongoing trace uses later to open a numbered change record. Setup does not run it.

### Trace only material changes

The change flow (open `docs/changes/{slug}/`, run the stages, record decisions) is for **significant** changes — global, structural, strategic, or design-level decisions with lasting consequences. Small, local, or cosmetic fixes skip it entirely; they are ordinary commits. The installed `AGENTS.md` states this threshold so agents neither over-record trivia nor let a big structural change slip by untraced.

## When to use

This is an **explicit, opt-in** skill. Only run it when the user has clearly asked for this setup — not just because the topic seems related.

- Use it when the user explicitly says something like "set up / install agentic tracing," "scaffold the docs/changes + PRD/design + AGENTS tracing," or "give my repo a change-record / decision-log system."
- Typical context: a brand-new or existing repo with no `docs/changes/` yet.

Do NOT trigger implicitly. If the user only mentions PRDs, doc structure, or that their agents keep forgetting decisions / drifting, you may *suggest* this skill, then wait for their explicit go-ahead. And do NOT use it to create a change or discuss requirements — those are separate flows.

## How to run

```bash
bash .cursor/skills/agentic-tracing/scripts/init.sh
```

The script is **idempotent**: existing files are never overwritten, only skipped and reported. After running:

1. Tell the user exactly what was created vs skipped.
2. **Stop there.** Do not create a change directory, start brainstorming, or gather requirements — that is separate, later work.
3. For an existing product adopting tracing, note that `docs/PRD.md` and `docs/design.md` should be back-filled with a current-state snapshot as follow-up (not part of setup).

## Notes

- **Scope guard:** this skill installs the tracing spine only. It never creates a change record or engages the user in requirements discussion. If asked to start a feature right after setup, hand off to the change flow — don't do it here.
- Do not treat `docs/PRD.md` / `docs/design.md` as templates to overwrite when they already have content. The script is safe (skip-if-exists); if the user explicitly asks to "re-init / overwrite," confirm once first.
- Decisions live in the `docs/design.md` Decisions section, fed from each merged change's `tech_design.md` — one place for the "why," not a separate file.
- The ongoing tracing rules (stages, materiality threshold, when to merge back, changelog status) live in the installed `AGENTS.md`. This skill just puts them in place.
