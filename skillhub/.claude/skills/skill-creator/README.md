# Skill Creator

Create new skills, modify and improve existing skills, and measure skill performance.

## What It Does

This skill guides agents through the full lifecycle of skill development: capturing user intent, interviewing for edge cases, writing the `SKILL.md` with proper frontmatter and triggering descriptions, creating test prompts, running evaluations, and iterating based on qualitative and quantitative feedback. It also includes a description optimizer for improving skill trigger accuracy. The skill adapts its communication style to the user's technical level, making it accessible to both developers and non-technical users.

Use this skill when users want to create a skill from scratch, edit or optimize an existing skill, run evaluations to test skill quality, or benchmark skill performance with variance analysis.

## Tags

`ai` · `workflow` · `testing`

## Risk Flags

| Flag | Status | Reason |
|------|--------|--------|
| `runs_scripts` | true | Contains evaluation runner scripts and review generation tools |
| `external_deps` | true | Requires dependencies for the eval-viewer and benchmark scripts |
