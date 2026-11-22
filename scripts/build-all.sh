#!/usr/bin/env bash
# Builds both APKs and the setup app for Windows, macOS and Linux (APKs embedded) into dist/.
set -euo pipefail
cp "$(dirname "$0")/../server/dispatcharr/lineup.full.example.json" "$(dirname "$0")/../setup/lineup-preset.json"
cd "$(dirname "$0")/.."
GRADLE="${GRADLE:-gradle}"
"$GRADLE" -q :launcher:assembleRelease :livetv:assembleRelease
mkdir -p dist setup/apks
cp launcher/build/outputs/apk/release/launcher-release.apk dist/vanilla-launcher.apk
cp livetv/build/outputs/apk/release/livetv-release.apk dist/vanilla-livetv.apk
cp dist/vanilla-*.apk setup/apks/
VERSION="${VERSION:-dev}"
cd setup
for target in linux/amd64 linux/arm64 darwin/amd64 darwin/arm64 windows/amd64; do
  os="${target%/*}"; arch="${target#*/}"; ext=""; [[ $os == windows ]] && ext=".exe"
  out="../dist/onn-vanilla-setup-${os}-${arch}${ext}"
  CGO_ENABLED=0 GOOS=$os GOARCH=$arch go build -trimpath -ldflags "-s -w" -o "$out" .
  echo "built $out"
done
