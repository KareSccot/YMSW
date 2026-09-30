# MCP Builder

Guide for creating high-quality MCP (Model Context Protocol) servers that enable LLMs to interact with external services through well-designed tools.

## What It Does

This skill walks agents through a four-phase process for building MCP servers: deep research and planning (studying the MCP spec and target API), implementation (project setup, core infrastructure, and tool creation with proper schemas and annotations), review and testing (code quality checks and MCP Inspector), and evaluation creation (generating 10 complex test questions). It supports both TypeScript (recommended) and Python stacks, with bundled reference guides for each.

Use this skill when building MCP servers to integrate external APIs or services, whether in TypeScript (MCP SDK) or Python (FastMCP).

## Tags

`mcp` · `integration` · `architecture`

## Risk Flags

| Flag | Status | Reason |
|------|--------|--------|
| `runs_scripts` | true | Contains build and test scripts |
| `sends_data` | true | MCP servers transmit data to external endpoints by design |
| `external_calls` | true | Makes network requests to external APIs |
| `reads_creds` | true | MCP servers typically handle authentication tokens and API keys |
| `external_deps` | true | Requires npm or pip packages for the MCP SDK |
