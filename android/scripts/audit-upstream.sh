#!/usr/bin/env bash
# Repeatable upstream audit for the Hermes Android client.
#
# Clones/updates the canonical backend and the community clients, then prints
# a focused diff report of the protocol-bearing files this app depends on
# (see docs/UPSTREAM.md). Run whenever hermes-agent main moves.
#
# Usage: scripts/audit-upstream.sh [workdir]
set -euo pipefail

WORKDIR="${1:-"${HOME}/.cache/hermes-android-upstream"}"
mkdir -p "$WORKDIR"

REPOS=(
  "hermes-agent https://github.com/NousResearch/hermes-agent"
  "luinbytes-android https://github.com/luinbytes/hermes-android"
  "rusty-android https://github.com/rusty4444/hermes-android"
)

# Files that define the contracts we implement (paths inside hermes-agent).
PROTOCOL_FILES=(
  "tui_gateway/ws.py"
  "tui_gateway/server.py"
  "tui_gateway/methods_session.py"
  "tui_gateway/methods_prompt.py"
  "tui_gateway/methods_profiles.py"
  "tui_gateway/methods_complete.py"
  "hermes_cli/web_routers/sessions.py"
  "hermes_cli/web_routers/profiles.py"
  "hermes_cli/dashboard_auth/ws_tickets.py"
  "hermes_cli/dashboard_auth/routes.py"
  "hermes_cli/dashboard_auth/public_paths.py"
  "apps/shared/src/json-rpc-gateway.ts"
  "apps/shared/src/websocket-url.ts"
  "apps/desktop/src/types/hermes.ts"
  "apps/desktop/src/hermes.ts"
)

echo "== Syncing upstreams into $WORKDIR"
for entry in "${REPOS[@]}"; do
  name="${entry%% *}"
  url="${entry##* }"
  dir="$WORKDIR/$name"
  if [ -d "$dir/.git" ]; then
    git -C "$dir" fetch --quiet origin
    git -C "$dir" reset --quiet --hard origin/HEAD
  else
    git clone --quiet --depth 50 "$url" "$dir"
  fi
  echo "  $name -> $(git -C "$dir" rev-parse --short HEAD)  $(git -C "$dir" log -1 --format=%cs)"
done

AGENT_DIR="$WORKDIR/hermes-agent"
STAMP_FILE="$WORKDIR/.last-audited-hermes-agent"
CURRENT="$(git -C "$AGENT_DIR" rev-parse HEAD)"

echo
echo "== hermes-agent protocol surface"
CONTRACT_LINE="$(grep -n "DESKTOP_BACKEND_CONTRACT" "$AGENT_DIR/tui_gateway/server.py" | head -1 || true)"
echo "  desktop contract: ${CONTRACT_LINE:-<not found — layout changed, re-audit manually>}"

if [ -f "$STAMP_FILE" ]; then
  LAST="$(cat "$STAMP_FILE")"
  if [ "$LAST" = "$CURRENT" ]; then
    echo "  no change since last audit ($CURRENT)"
  else
    echo "  changes since last audit ($LAST -> $CURRENT):"
    for f in "${PROTOCOL_FILES[@]}"; do
      if ! git -C "$AGENT_DIR" diff --quiet "$LAST" "$CURRENT" -- "$f" 2>/dev/null; then
        stat="$(git -C "$AGENT_DIR" diff --stat "$LAST" "$CURRENT" -- "$f" | tail -1)"
        echo "    CHANGED $f  ($stat)"
      fi
    done
    echo
    echo "  Review the diffs above, update :protocol and docs/UPSTREAM.md, then:"
    echo "    echo $CURRENT > $STAMP_FILE"
  fi
else
  echo "  first run — baseline recorded"
  echo "$CURRENT" > "$STAMP_FILE"
fi

echo
echo "== Community clients (reference only — do not merge UI)"
for name in luinbytes-android rusty-android; do
  dir="$WORKDIR/$name"
  echo "  $name: last 5 commits"
  git -C "$dir" log -5 --format='    %h %cs %s' || true
done

echo
echo "Done. Contract citations live in docs/UPSTREAM.md."
