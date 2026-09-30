---
name: finishing-a-development-branch
description: Use when implementation is complete, all tests pass, and you need to decide how to integrate the work - guides completion of development work by presenting structured options for merge, PR, or cleanup
---

# Finishing a Development Branch

## Overview

Guide completion of development work by presenting clear options and handling chosen workflow.

**Core principle:** Verify tests → Detect environment → Present options → Execute choice → Clean up.

## The Process

### Step 1: Verify Tests

Run project's test suite. If tests fail, STOP — cannot proceed until fixed.

### Step 2: Detect Environment

Determine if in normal repo or git worktree to decide cleanup behavior.

### Step 3: Determine Base Branch

Find the branch this work split from (usually main/master).

### Step 4: Present Options

**Present exactly these 4 options:**

```
Implementation complete. What would you like to do?

1. Merge back to <base-branch> locally
2. Push and create a Pull Request
3. Keep the branch as-is (I'll handle it later)
4. Discard this work

Which option?
```

### Step 5: Execute Choice

**Option 1: Merge Locally**
- Checkout base branch, pull, merge feature branch
- Verify tests on merged result
- Cleanup worktree, delete branch

**Option 2: Push and Create PR**
- Push branch to origin
- Create PR with structured description
- Do NOT clean up worktree (needed for PR iteration)

**Option 3: Keep As-Is**
- Report branch location
- Don't cleanup

**Option 4: Discard**
- Require typed "discard" confirmation
- Remove worktree, force-delete branch

## Quick Reference

| Option | Merge | Push | Keep Worktree | Cleanup Branch |
|--------|-------|------|---------------|----------------|
| 1. Merge locally | yes | - | - | yes |
| 2. Create PR | - | yes | yes | - |
| 3. Keep as-is | - | - | yes | - |
| 4. Discard | - | - | - | yes (force) |

## Red Flags

**Never:**
- Proceed with failing tests
- Merge without verifying tests on result
- Delete work without confirmation
- Force-push without explicit request
- Remove worktrees you didn't create
