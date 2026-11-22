#!/bin/sh
# One-command server setup: Docker + Dispatcharr + nightly channel-list refresh.
#   git clone https://github.com/neilyboy/onn-vanilla && cd onn-vanilla/server && ./install.sh
# Re-running is safe; it keeps your Dispatcharr data and lineup.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
CONF=${XDG_CONFIG_HOME:-$HOME/.config}/onn-vanilla
say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

if ! command -v docker >/dev/null 2>&1; then
  say "Installing Docker"
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker "$USER" || true
  echo "Docker installed. If the next step says 'permission denied', log out and back in, then re-run ./install.sh"
fi
DOCKER="docker"; docker info >/dev/null 2>&1 || DOCKER="sudo docker"

say "Starting Dispatcharr"
export TZ=${TZ:-$(cat /etc/timezone 2>/dev/null || timedatectl show -p Timezone --value 2>/dev/null || echo UTC)}
(cd "$HERE" && $DOCKER compose up -d dispatcharr)

mkdir -p "$CONF"
if [ ! -f "$CONF/lineup.json" ]; then
  say "Your channel list"
  printf "Local TV market for your ABC/CBS/NBC/FOX (city and/or call letters, e.g. Springfield|KYTV). Blank to skip: "
  read -r LOCALS || LOCALS=""
  LOCALS=${LOCALS:-NO_LOCALS_SET}
  sed "s/YOUR_CITY|YOUR_CALL_LETTERS/$LOCALS/" "$HERE/dispatcharr/lineup.full.example.json" > "$CONF/lineup.json"
  echo "Saved $CONF/lineup.json (edit it any time)."
fi

say "Nightly refresh at 4:30 AM"
chmod +x "$HERE/refresh-lineup.sh"
JOB="30 4 * * * DOCKER_HOST=${DOCKER_HOST:-} $HERE/refresh-lineup.sh >> $CONF/refresh.log 2>&1"
( crontab -l 2>/dev/null | grep -v refresh-lineup.sh; echo "$JOB" ) | crontab -

IP=$(hostname -I 2>/dev/null | awk '{print $1}'); IP=${IP:-localhost}
cat <<MSG

Done. Next:
  1. Open http://$IP:9191, create your admin login.
  2. M3U & EPG Manager -> Add M3U -> Xtream Codes, once per provider (URL, username, password).
     Wait for each to finish loading.
  3. Build your channel list:  $HERE/refresh-lineup.sh
     (or the "Build my channel list" button in the setup app). It re-runs nightly by itself.
  4. Run the setup app to configure and flash your TV boxes.
Your playlist: http://$IP:9191/output/m3u   Guide: http://$IP:9191/output/epg
MSG
