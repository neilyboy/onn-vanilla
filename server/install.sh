#!/bin/sh
# One-command server setup: Docker + Dispatcharr + the setup wizard, on any Linux server (Ubuntu Server is tested).
#   git clone https://github.com/neilyboy/onn-vanilla && cd onn-vanilla/server && ./install.sh
# Re-running is safe; it keeps your Dispatcharr data and wizard settings.
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

say "Starting Dispatcharr and the setup wizard"
export TZ=${TZ:-$(cat /etc/timezone 2>/dev/null || timedatectl show -p Timezone --value 2>/dev/null || echo UTC)}
(cd "$HERE" && $DOCKER compose up -d --build dispatcharr setup)

IP=$(hostname -I 2>/dev/null | awk '{print $1}'); IP=${IP:-localhost}
cat <<MSG

Done. Next:
  1. Open http://$IP:9191 and create your Dispatcharr admin login.
  2. M3U & EPG Manager -> Add M3U -> Xtream Codes, once per provider. Wait for each to finish loading.
  3. Plugins -> Import the Lineup Manager plugin (https://github.com/neilyboy/dispatcharr-lineup-manager),
     type your ZIP code, press Build lineup, then Turn on schedule.
  4. Open the setup wizard at http://$IP:8765 from a phone or computer on this network and choose its password.
     After that it works from anywhere you can reach this server (see the README for HTTPS on your own domain).
Your playlist: http://$IP:9191/output/m3u   Guide: http://$IP:9191/output/epg
MSG
