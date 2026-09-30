#!/usr/bin/env bash
# Idempotently install the agentic-tracing spine at the repo root.
# Existing files are never overwritten, only skipped and reported at the end.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATES_DIR="$SKILL_DIR/templates"
REPO_ROOT="$(cd "$SKILL_DIR/../../.." && pwd)"

created=()
skipped=()

copy_if_absent() {
  local src="$1" dest="$2"
  if [ -e "$dest" ]; then
    skipped+=("$dest")
  else
    mkdir -p "$(dirname "$dest")"
    cp "$src" "$dest"
    created+=("$dest")
  fi
}

mkdir -p "$REPO_ROOT/docs/changes"
mkdir -p "$REPO_ROOT/.cursor/hooks"

copy_if_absent "$TEMPLATES_DIR/PRD.md"                          "$REPO_ROOT/docs/PRD.md"
copy_if_absent "$TEMPLATES_DIR/design.md"                       "$REPO_ROOT/docs/design.md"
copy_if_absent "$TEMPLATES_DIR/changelog.md"                    "$REPO_ROOT/docs/changes/changelog.md"
copy_if_absent "$TEMPLATES_DIR/AGENTS.md"                       "$REPO_ROOT/AGENTS.md"
copy_if_absent "$TEMPLATES_DIR/hooks.json"                      "$REPO_ROOT/.cursor/hooks.json"
copy_if_absent "$TEMPLATES_DIR/hooks/check-change-artifacts.sh" "$REPO_ROOT/.cursor/hooks/check-change-artifacts.sh"

chmod +x "$REPO_ROOT/.cursor/hooks/check-change-artifacts.sh" 2>/dev/null || true

echo "=== Agentic tracing installed ==="
if [ "${#created[@]}" -gt 0 ]; then
  echo "Created:"
  for f in "${created[@]}"; do echo "  + ${f#$REPO_ROOT/}"; done
else
  echo "Created: (none, everything already existed)"
fi
if [ "${#skipped[@]}" -gt 0 ]; then
  echo "Skipped (already existed):"
  for f in "${skipped[@]}"; do echo "  = ${f#$REPO_ROOT/}"; done
fi
echo ""
echo "The agentic-tracing spine is installed (structure + snapshots + AGENTS.md + hooks)."
echo "Tracing runs automatically from here via AGENTS.md and the hooks."
echo "This setup does NOT create a change or gather requirements — those are separate steps."
echo "Only material changes get traced; see AGENTS.md for the threshold and workflow."
