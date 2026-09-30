# DOCX

Create, read, edit, and manipulate Word documents (`.docx` files).

## What It Does

This skill provides comprehensive guidance for working with `.docx` files. It covers three main workflows: reading and extracting content (via `pandoc` or raw XML), creating new documents programmatically (via `docx-js` with JavaScript), and editing existing documents by unpacking, modifying XML, and repacking. The skill includes detailed references for tables, images, tracked changes, comments, headers/footers, and more.

Use this skill whenever the user mentions Word documents, `.docx` files, or requests professionally formatted documents with features like tables of contents, headings, page numbers, or letterheads.

## Tags

`documents` · `tooling` · `workflow`

## Risk Flags

| Flag | Status | Reason |
|------|--------|--------|
| `runs_scripts` | true | Contains Python and JavaScript helper scripts for document processing |
| `external_deps` | true | Requires `pandoc`, `docx` (npm), and LibreOffice for full functionality |
