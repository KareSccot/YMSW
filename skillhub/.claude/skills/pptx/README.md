# PPTX

Create, read, edit, and manipulate PowerPoint presentations (`.pptx` files).

## What It Does

This skill provides a complete workflow for working with `.pptx` files. It supports three modes: reading content (via `markitdown` or raw XML extraction), editing existing presentations (unpack, manipulate slides, repack), and creating new decks from scratch (via `pptxgenjs`). The skill also includes opinionated design guidance — bold color palettes, distinctive visual motifs, and slide-by-slide composition tips — to ensure presentations look polished and professional rather than generic.

Use this skill any time a `.pptx` file is involved as input, output, or both, including creating slide decks, extracting text, editing layouts, or working with templates and speaker notes.

## Tags

`documents` · `content` · `tooling`

## Risk Flags

| Flag | Status | Reason |
|------|--------|--------|
| `runs_scripts` | true | Contains Python and JavaScript helper scripts for slide processing |
| `external_deps` | true | Requires `pptxgenjs` (npm), `markitdown` (pip), and LibreOffice |
