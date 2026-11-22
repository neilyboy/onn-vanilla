#!/bin/sh
# Rebuild the Dispatcharr channel list from your providers (merge duplicates, add new
# channels, drop dead ones, sync stream limits). Safe to run any time; install.sh runs it nightly.
#   ./refresh-lineup.sh            apply
#   ./refresh-lineup.sh --dry-run  just show what would change
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
LINEUP=${LINEUP:-${XDG_CONFIG_HOME:-$HOME/.config}/onn-vanilla/lineup.json}
CONTAINER=${CONTAINER:-dispatcharr}
[ -f "$LINEUP" ] || { echo "No lineup at $LINEUP (run install.sh or copy dispatcharr/lineup.full.example.json there)"; exit 1; }
APPLY=1; [ "${1:-}" = "--dry-run" ] && APPLY=0
echo "== $(date) refreshing lineup ($LINEUP)"
docker exec -i -e CURATE_CONFIG="$(cat "$LINEUP")" -e CURATE_APPLY=$APPLY -e CURATE_SYNC_LIMITS=$APPLY \
  "$CONTAINER" python /app/manage.py shell < "$HERE/dispatcharr/curate.py" 2>&1 | grep -E '^(==|provider|APPLIED|DRY RUN|  [a-zA-Z].*limit)|Error|Traceback' || true
