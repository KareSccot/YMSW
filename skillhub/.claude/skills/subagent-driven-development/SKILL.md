---
name: subagent-driven-development
description: Use when executing implementation plans with independent tasks in the current session
---

# Subagent-Driven Development

Execute plan by dispatching fresh subagent per task, with two-stage review after each: spec compliance review first, then code quality review.

**Why subagents:** You delegate tasks to specialized agents with isolated context. By precisely crafting their instructions and context, you ensure they stay focused and succeed at their task.

**Core principle:** Fresh subagent per task + two-stage review (spec then quality) = high quality, fast iteration

**Continuous execution:** Do not pause to check in with your human partner between tasks. Execute all tasks from the plan without stopping. The only reasons to stop are: BLOCKED status you cannot resolve, ambiguity that genuinely prevents progress, or all tasks complete.

## When to Use

- Have implementation plan with mostly independent tasks
- Want to stay in this session (vs. parallel session)
- Need fresh context per task (no pollution)
- Want two-stage review after each task

## The Process

1. Read plan, extract all tasks with full text
2. Create TodoWrite with all tasks
3. For each task:
   - Dispatch implementer subagent with full task text + context
   - Handle questions if implementer asks
   - Implementer implements, tests, commits, self-reviews
   - Dispatch spec reviewer subagent → confirms code matches spec
   - Dispatch code quality reviewer subagent → approves quality
   - Mark task complete
4. After all tasks: dispatch final code reviewer for entire implementation
5. Use finishing-a-development-branch skill

## Handling Implementer Status

- **DONE:** Proceed to spec compliance review
- **DONE_WITH_CONCERNS:** Read concerns, address if about correctness
- **NEEDS_CONTEXT:** Provide missing context, re-dispatch
- **BLOCKED:** Assess blocker, provide context or escalate

## Model Selection

- **Mechanical tasks** (1-2 files, clear spec): use fast/cheap model
- **Integration tasks** (multi-file): use standard model
- **Architecture/review tasks**: use most capable model

## Red Flags

**Never:**
- Skip reviews (spec compliance OR code quality)
- Proceed with unfixed issues
- Dispatch multiple implementation subagents in parallel (conflicts)
- Make subagent read plan file (provide full text instead)
- Start code quality review before spec compliance is approved
- Move to next task while review has open issues

**If reviewer finds issues:**
- Implementer fixes them
- Reviewer reviews again
- Repeat until approved

## Advantages

- Fresh context per task (no confusion)
- Two-stage review catches issues early
- Subagents follow TDD naturally
- Parallel-safe (subagents don't interfere)
- Self-review + two-stage review = high quality
