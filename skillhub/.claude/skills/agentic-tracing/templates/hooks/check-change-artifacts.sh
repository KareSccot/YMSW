#!/usr/bin/env bash
# Agentic tracing — non-blocking reminder hook (can be attached to afterFileEdit / stop).
# Checks whether every traced change (docs/changes/{slug}/) has a matching row in changelog.md.
# Reminder only, never blocks anything, always exits 0.

set -uo pipefail

# Consume the hook payload from stdin (contents not needed here)
cat > /dev/null 2>&1 || true

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
CHANGES_DIR="$REPO_ROOT/docs/changes"
CHANGELOG="$CHANGES_DIR/changelog.md"

[ -d "$CHANGES_DIR" ] || exit 0
[ -f "$CHANGELOG" ] || exit 0

for dir in "$CHANGES_DIR"/*/; do
  [ -d "$dir" ] || continue
  slug="$(basename "$dir")"
  if ! grep -q "$slug" "$CHANGELOG" 2>/dev/null; then
    echo "[agentic-tracing] Reminder: docs/changes/$slug/ exists but has no matching row in changelog.md. Add one so the change stays traced." >&2
  fi
done

exit 0
