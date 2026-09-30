# Git Commit

Create well-structured git commits with automatic hook failure recovery.

## What It Does

Guides creating git commits following the Conventional Commits specification. Analyzes staged diffs and recent commit history to draft structured messages (`feat`, `fix`, `chore`, etc.). When git hooks (pre-commit, commit-msg, prepare-commit-msg) reject the commit, automatically diagnoses the failure, applies the appropriate fix, and retries — up to 3 attempts — until the commit succeeds locally.

## Tags

`agentic-sdlc` · `phase:5_review` · `git` · `workflow`

## Risk Flags

- `runs_scripts`: true — executes git commands and may run project linters/formatters to fix hook failures
