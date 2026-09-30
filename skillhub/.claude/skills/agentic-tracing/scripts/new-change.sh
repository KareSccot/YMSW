#!/usr/bin/env bash
# Agentic tracing — open a new traced change (auto-numbered) and append an index row to changelog.md.
# Use only for material changes (see AGENTS.md); trivial/local work does not get traced.
# Usage: new-change.sh <kebab-case-name> ["<title>"]
#   For the first call, use: new-change.sh init "<Product name> MVP"  -> produces 000-init
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

NAME="${1:?Usage: new-change.sh <kebab-case-name> \"<title>\"}"
TITLE="${2:-$NAME}"

CHANGES_DIR="$REPO_ROOT/docs/changes"
mkdir -p "$CHANGES_DIR"

LAST=$(find "$CHANGES_DIR" -maxdepth 1 -mindepth 1 -type d -name '[0-9][0-9][0-9]-*' 2>/dev/null \
  | sed -E 's#.*/([0-9]{3})-.*#\1#' | sort -n | tail -1 || true)

if [ -z "${LAST:-}" ]; then
  NEXT="000"
else
  NEXT=$(printf "%03d" $((10#$LAST + 1)))
fi

SLUG="${NEXT}-${NAME}"
DEST="$CHANGES_DIR/$SLUG"

if [ -e "$DEST" ]; then
  echo "Error: $DEST already exists" >&2
  exit 1
fi

mkdir -p "$DEST"
touch "$DEST/prd.md" "$DEST/mockup.html" "$DEST/tech_design.md" "$DEST/plan.md" "$DEST/review.md"

CHANGELOG="$CHANGES_DIR/changelog.md"
if [ ! -f "$CHANGELOG" ]; then
  {
    echo "# Changelog"
    echo ""
    echo "The index of every change (init or iteration), appended newest first. The status should be kept in sync with each change's Review outcome."
    echo ""
    echo "| Slug | Title | Type | Date | Status | Summary |"
    echo "|---|---|---|---|---|---|"
  } > "$CHANGELOG"
fi

TYPE="iteration"
[ "$NEXT" = "000" ] && TYPE="init"

echo "| $SLUG | $TITLE | $TYPE | $(date +%Y-%m-%d) | planning | |" >> "$CHANGELOG"

echo "Created: docs/changes/$SLUG/"
echo "Appended an index row to changelog.md (status: planning)"
echo ""
echo "Next: start stage 1 (Spec/PRD) and produce docs/changes/$SLUG/prd.md"
