# Add AD SSO

Canonical pattern for adding Active Directory (Azure AD / Microsoft Entra ID) single sign-on to an application.

## What It Does

This skill captures the company's standard approach to AD/Entra ID login, generalized so it can be reproduced in any language or framework. It uses the OAuth2 Authorization Code flow to learn *who the user is*, just-in-time provisions a local user, then issues the application's **own** short-lived JWT session — so the API is decoupled from the IdP and the Microsoft token never stays on the wire after login.

The skill describes each step as a language-agnostic contract (responsibility, input, output), then provides a Python (Django Ninja) backend demo, a framework-agnostic TypeScript (Express) backend demo, and a TypeScript frontend demo (login entry, callback, Bearer injection, refresh-on-401). It closes with a configuration checklist, a production security/best-practices checklist, common pitfalls in minimal implementations, and a pre-ship integration checklist.

Use this skill when implementing AD/Azure AD/Entra login or SSO, wiring an "Employee Login" button, building an `/aad/login` redirect or `/aad/callback` handler, or reviewing/hardening an existing OAuth2 Authorization Code login.

## Tags

`agentic-sdlc` · `phase:3_build` · `security` · `integration`

## Risk Flags

All flags are `false` — this is a pure-prompt skill with no scripts, network access, or external dependencies.
