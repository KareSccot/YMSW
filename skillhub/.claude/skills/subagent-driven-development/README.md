# Subagent-Driven Development

Execute plans by dispatching fresh subagent per task.

## What It Does

The core execution engine for Superpowers methodology. Dispatches a fresh subagent for each task in the implementation plan, with two-stage review after each: spec compliance review first, then code quality review. Fresh context per task prevents pollution. Continuous execution without pausing for check-ins.

## Source

Copied from [obra/superpowers](https://github.com/obra/superpowers) v5.1.0 (MIT License).

## Tags

`agentic-sdlc` · `phase:3_build` · `workflow`

## Risk Flags

- `runs_scripts`: true — subagents execute implementation, tests, and git commands
