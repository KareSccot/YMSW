# PDF

Process, create, and manipulate PDF files.

## What It Does

This skill provides comprehensive guidance for PDF operations using Python libraries and command-line tools. It covers reading and extracting text or tables, merging and splitting PDFs, rotating pages, adding watermarks, creating new PDFs, filling PDF forms, encrypting/decrypting, extracting images, and performing OCR on scanned documents. The primary library is `pypdf`, with additional support from `reportlab` for creation and `pdftoppm` for image conversion.

Use this skill whenever the user wants to do anything with PDF files — reading, creating, merging, splitting, form-filling, or converting.

## Tags

`documents` · `tooling` · `workflow`

## Risk Flags

| Flag | Status | Reason |
|------|--------|--------|
| `runs_scripts` | true | Contains Python helper scripts for PDF processing (validate, convert, OCR) |
| `external_deps` | true | Requires `pypdf`, `reportlab`, LibreOffice, and Poppler tools |
