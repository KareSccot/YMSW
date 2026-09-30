# Systematic Debugging

4-phase root cause debugging process.

## What It Does

Enforces systematic debugging over random fixes. Iron law: NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST. Four phases: (1) Root Cause Investigation — read errors, reproduce, check recent changes, gather evidence; (2) Pattern Analysis — find working examples, compare; (3) Hypothesis Testing — form theory, test minimally; (4) Implementation — create failing test, fix, verify.

## Source

Copied from [obra/superpowers](https://github.com/obra/superpowers) v5.1.0 (MIT License).

## Tags

`agentic-sdlc` · `phase:3_build` · `workflow`

## Risk Flags

- `runs_scripts`: true — executes diagnostic commands and test verification
