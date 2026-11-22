#!/usr/bin/env bash
# Re-installs every package that existed in a backup but is now missing for user 0, and re-enables disabled ones.
# Usage: ./restore-onn.sh <BOX_IP> backups/<serial>-<stamp>.all.txt
set -euo pipefail
BOX_IP="${1:?Usage: $0 <BOX_IP> <backup.all.txt>}"; BACKUP="${2:?backup file}"
ADB="${ADB:-$(command -v adb || echo "$HOME/platform-tools/adb")}"
T="${BOX_IP}:5555"; [[ "$BOX_IP" == *:* ]] && T="$BOX_IP"
"$ADB" connect "$T" >/dev/null; "$ADB" -s "$T" wait-for-device
NOW=$("$ADB" -s "$T" shell pm list packages | tr -d '\r' | sed 's/^package://')
sed -E 's/^package:.*=//' "$BACKUP" | tr -d '\r' | while read -r pkg; do
  grep -qx "$pkg" <<<"$NOW" && continue
  echo "restoring $pkg"
  "$ADB" -s "$T" shell cmd package install-existing --user 0 "$pkg" </dev/null >/dev/null || true
done
"$ADB" -s "$T" shell pm list packages -d | tr -d '\r' | sed 's/^package://' | while read -r pkg; do
  grep -qx "package:$pkg" "${BACKUP%.all.txt}.disabled.txt" 2>/dev/null && continue
  echo "enabling $pkg"; "$ADB" -s "$T" shell pm enable "$pkg" </dev/null >/dev/null || true
done
echo "Restore complete; reboot the box."
