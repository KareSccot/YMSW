# Finishing a Development Branch

Guide completion of development work with structured options.

## What It Does

Guides the final step of a development workflow. Verifies all tests pass, detects the workspace environment (normal repo vs. worktree), then presents exactly 4 options: (1) Merge locally, (2) Push and create PR, (3) Keep as-is, (4) Discard. Handles cleanup of worktrees and branches based on choice.

## Source

Copied from [obra/superpowers](https://github.com/obra/superpowers) v5.1.0 (MIT License).

## Tags

`agentic-sdlc` · `phase:6_ship` · `workflow`

## Risk Flags

- `runs_scripts`: true — executes git commands for merge, push, branch cleanup
