#!/usr/bin/env bash
# One-shot provisioning for Onn / Android TV boxes.
# Usage: ./provision-onn.sh <BOX_IP> [--dry-run] [--no-reboot] [--adguard-dns] [--keep-play-store]
set -euo pipefail
cd "$(dirname "$0")"
source ./provision.conf
[[ -f ./provision.local.conf ]] && source ./provision.local.conf
[[ -n "$M3U_URL" || -n "$CONFIG_URL" ]] || { echo "Set DISPATCHARR_URL (or CONFIG_URL) in provision.local.conf first."; exit 1; }

BOX_IP="${1:?Usage: $0 <BOX_IP> [--dry-run] [--no-reboot] [--adguard-dns] [--keep-play-store]}"
shift || true
DRY=0; REBOOT=1; ADGUARD=0; KEEP_STORE=1
for a in "$@"; do
  case "$a" in
    --dry-run) DRY=1 ;;
    --no-reboot) REBOOT=0 ;;
    --adguard-dns) ADGUARD=1 ;;
    --remove-play-store) KEEP_STORE=0 ;;
    --keep-play-store) KEEP_STORE=1 ;;
    *) echo "Unknown option: $a"; exit 1 ;;
  esac
done

ADB="${ADB:-$(command -v adb || echo "$HOME/platform-tools/adb")}"
T="${BOX_IP}:5555"
[[ "$BOX_IP" == *:* ]] && T="$BOX_IP"
sh_() { "$ADB" -s "$T" shell "$@"; }
run() { if (( DRY )); then echo "  [dry-run] $*"; else "$@"; fi; }
step() { echo; echo "==> $*"; }

step "Connecting to $T"
"$ADB" connect "$T" >/dev/null
echo "  (accept 'Allow USB debugging' on the TV if prompted)"
timeout 60 "$ADB" -s "$T" wait-for-device
MODEL=$(sh_ getprop ro.product.model | tr -d '\r')
SDK=$(sh_ getprop ro.build.version.sdk | tr -d '\r')
ABI=$(sh_ getprop ro.product.cpu.abi | tr -d '\r')
SERIAL=$(sh_ getprop ro.serialno | tr -d '\r')
echo "  $MODEL  |  Android SDK $SDK  |  $ABI  |  serial $SERIAL"
[[ "$(sh_ settings get secure user_setup_complete | tr -d '\r')" == "1" ]] || { echo "Finish the on-screen setup wizard first."; exit 1; }

step "Backing up package state"
mkdir -p backups
B="backups/${SERIAL:-$BOX_IP}-$(date +%Y%m%d-%H%M%S)"
sh_ pm list packages -f > "$B.all.txt"
sh_ pm list packages -d > "$B.disabled.txt"
sh_ pm list packages -3 > "$B.user.txt"
echo "  saved $B.*.txt"

step "Installing apps"
for apk in "${APKS[@]}"; do
  [[ -f "$apk" ]] || { echo "  missing $apk"; exit 1; }
  echo "  $apk"
  run "$ADB" -s "$T" install -r -g "$apk" >/dev/null
done
if (( ! DRY )); then
  sh_ pm path "$LAUNCHER_PKG" >/dev/null || { echo "Launcher failed to install; aborting before debloat."; exit 1; }
fi

step "Removing bloat (per-user uninstall; restore-onn.sh undoes it)"
REMOVE=("${BLOAT[@]}")
(( KEEP_STORE )) || REMOVE+=("com.android.vending")
INSTALLED=$(sh_ pm list packages | tr -d '\r' | sed 's/^package://')
for pkg in "${REMOVE[@]}"; do
  if grep -qx "$pkg" <<<"$INSTALLED"; then
    echo "  - $pkg"
    run sh_ pm uninstall -k --user 0 "$pkg" >/dev/null 2>&1 || run sh_ pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || echo "    (could not remove)"
  fi
done

step "Setting Vanilla Home as the home screen"
run sh_ cmd package set-home-activity "$LAUNCHER_PKG/.MainActivity" >/dev/null || true
run sh_ cmd role add-role-holder --user 0 android.app.role.HOME "$LAUNCHER_PKG" >/dev/null 2>&1 || true
# Google TV always sends Home to its stock launcher (and loops on setup-recovery without it),
# so keep it installed and let Vanilla Home's accessibility service catch the Home button.
for stock in com.google.android.apps.tv.launcherx com.google.android.tvlauncher; do
  run sh_ cmd package install-existing "$stock" >/dev/null 2>&1 || true
done
SVC="$LAUNCHER_PKG/$LAUNCHER_PKG.HomeButtonService"
CUR=$(sh_ settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *"$SVC"*) ;; ""|null) run sh_ settings put secure enabled_accessibility_services "$SVC" ;; *) run sh_ settings put secure enabled_accessibility_services "$CUR:$SVC" ;; esac
run sh_ settings put secure accessibility_enabled 1
run sh_ pm grant "$LAUNCHER_PKG" android.permission.WRITE_SECURE_SETTINGS

step "Pointing apps at the server"
run sh_ am start -n "$LIVETV_PKG/.MainActivity" --es config_url "$CONFIG_URL" --es m3u_url "$M3U_URL" --es epg_url "$EPG_URL" >/dev/null
sleep 3
run sh_ am start -n "$LAUNCHER_PKG/.MainActivity" --es config_url "$CONFIG_URL" >/dev/null

step "System tweaks"
run sh_ settings put global window_animation_scale 0.5
run sh_ settings put global transition_animation_scale 0.5
run sh_ settings put global animator_duration_scale 0.5
[[ -n "${TIMEZONE:-}" ]] && run sh_ service call alarm 3 s16 "$TIMEZONE" >/dev/null 2>&1 || true
if (( ADGUARD )); then
  run sh_ settings put global private_dns_mode hostname
  run sh_ settings put global private_dns_specifier dns.adguard-dns.com
fi

step "Remaining third-party apps"
sh_ pm list packages -3 | tr -d '\r' | sed 's/^package:/  /'

if (( REBOOT && ! DRY )); then
  step "Rebooting"
  "$ADB" -s "$T" reboot
fi
echo; echo "Done. Backup: $B.*.txt"
