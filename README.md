<div align="center">

# Onn Vanilla

**Turn any Android TV / Google TV box into a clean, fast, fully yours TV appliance.**

A minimal launcher with widgets, a game-day IPTV player with Movies & Series, and a one-click setup wizard. No bloat, no ads, nothing personal baked in.

[![License: MIT](https://img.shields.io/badge/license-MIT-black.svg)](LICENSE)
![Android TV](https://img.shields.io/badge/Android%20TV-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose%20for%20TV-7F52FF?logo=kotlin&logoColor=white)
![Go](https://img.shields.io/badge/setup-Go-00ADD8?logo=go&logoColor=white)
![Dispatcharr](https://img.shields.io/badge/backend-Dispatcharr-E5484D)

<img src="docs/screenshots/launcher-mirror.jpg" alt="Vanilla Home with the Mirror widget layout" width="900">

[Features](#features) · [Screenshots](#screenshots) · [Quick start](#quick-start) · [Building](#building)

</div>

## Features

<table>
<tr>
<td width="50%" valign="top">

### Vanilla Home
- Plain launcher: clock, weather, 7-day forecast, only the apps you pick
- **Widgets** in Minimal, Glance or Mirror layouts: calendar (iCal) with countdowns, news cards (any RSS), scores, hourly weather + live radar, weather alerts (NWS)
- **From Live TV:** On now, Continue watching, New on Movies & Series. OK tunes straight in
- **Home Assistant** switches, lights, locks and camera snapshots
- **Idle photo frame** from Immich or Google Photos shared albums
- Themes, accent colors, monochrome / tinted icon styles, Play Store button
- One button back to the original Google TV home

</td>
<td width="50%" valign="top">

### Live TV
- Full TV guide, favorites, recents, search, number entry, last channel
- **Game day:** your teams' live games up top, one press to the right channel, score bar over whatever you're watching, kickoff reminders
- **Multiview:** 2–4 games at once, you pick the channels
- **Movies & Series** from your providers, resume per profile
- **Profiles** with colors, PINs and a Kids mode
- **Dispatcharr** by default, plus Xtream Codes and M3U sources added right on the TV
- Empty event slots stay hidden until a game is on

</td>
</tr>
<tr>
<td valign="top">

### Setup wizard
- Runs on Windows, macOS or Linux and opens in your browser
- Walks you through wireless debugging and pairing
- Detects the device and only removes bloat known to be safe for it (reversible)
- Pushes your theme, widgets, sports and profiles to every box
- Export / import your settings

</td>
<td valign="top">

### Channel lineup (Dispatcharr)
- Merges every provider into **one** guide, with automatic backups per channel
- Respects each provider's stream limit
- Keeps your country (plus any you tick, e.g. UK sports and 4K)
- **What's left out:** browse every hidden group and add any back
- Rebuilds itself when you add or drop a provider

</td>
</tr>
</table>

## Screenshots

| Live TV home | Sports |
|---|---|
| ![Live TV home](docs/screenshots/livetv-home.jpg) | ![Sports](docs/screenshots/livetv-sports.jpg) |
| **Guide** | **Settings** |
| ![Guide](docs/screenshots/livetv-guide.jpg) | ![Settings](docs/screenshots/livetv-settings.jpg) |
| **Movies** | **Series** |
| ![Movies](docs/screenshots/livetv-movies.jpg) | ![Series](docs/screenshots/livetv-series.jpg) |

<details>
<summary><b>Setup wizard</b></summary>

| Live TV source | Home screen |
|---|---|
| ![Live TV source](docs/screenshots/wizard-source.jpg) | ![Home screen](docs/screenshots/wizard-home.jpg) |
| **Sports** | **Set up a box** |
| ![Sports](docs/screenshots/wizard-sports.jpg) | ![Set up a box](docs/screenshots/wizard-box.jpg) |

</details>

Nothing personal ships in this repo. The setup app keeps your settings on your computer only.

## Quick start

### 1. Server (once)

On any always-on Linux machine (Ubuntu Server is tested):

```sh
git clone https://github.com/neilyboy/onn-vanilla && cd onn-vanilla/server && ./install.sh
```

This installs Docker if needed and starts Dispatcharr on port 9191 and the setup wizard on port 8765. Re-running it on a new server sets everything up the same way. Then:

1. Open `http://<server>:9191` and create your Dispatcharr admin login.
2. **M3U & EPG Manager → Add M3U → Xtream Codes**, once per provider (server URL, username, password). Wait for each to finish loading.
3. Build the channel list: install the [Lineup Manager plugin](https://github.com/neilyboy/dispatcharr-lineup-manager) (Dispatcharr → Plugins → Import), type your ZIP code, press **Build lineup**, then **Turn on schedule**.
4. Open the wizard at `http://<server>:8765` from a phone or computer on your network and **choose its password**. The first password can only be set from your home network, not through a reverse proxy.

#### Opening the wizard from anywhere

The wizard has its own password, and a signed-in phone stays signed in for 6 months. To reach it away from home:

- **Your own domain (HTTPS):** point a subdomain at your reverse proxy and forward it to `http://<server>:8765`. With Caddy: `setup.example.com { reverse_proxy 192.168.1.10:8765 }`. Nginx Proxy Manager and Traefik work the same way. Don't expose port 8765 without HTTPS.
- **No domain:** install [Tailscale](https://tailscale.com) on the server and your phone, then open `http://<server's Tailscale name>:8765`.
- **Forgot the password:** `docker exec onn-vanilla-setup onn-vanilla-setup --reset-password`, then open the wizard from your home network to choose a new one. **Change password** and **Sign out** are at the bottom left of the wizard.

**Boxes at another house:** the wizard flashes a box over the network, so the server must be able to reach the box's IP. A box on a network linked to yours (site-to-site VPN, or the same Tailscale network as a subnet router) works from your phone like one at home. Otherwise run the desktop setup app (below) on a laptop at that house.

### 2. Your channel list

Providers list the same channel many times (once per provider, plus HD/FHD/backup copies) and mix in event slots and foreign groups. The [Lineup Manager plugin](https://github.com/neilyboy/dispatcharr-lineup-manager) (or the same rules in [`server/dispatcharr/curate.py`](server/dispatcharr/curate.py) for the command line) turns that into one tidy guide:

- **Merges duplicates** of linear channels (ESPN, CNN, locals…) across providers by guide ID or name. Each merged channel keeps every copy as a backup, best provider first. If a provider is down or at its limit, Dispatcharr switches to the next.
- **Reads each provider's real stream limit** from its Xtream account and sets it in Dispatcharr.
- **Sorts everything into groups**, sports first: Locals, Sports, News, Entertainment, Movies, Kids, Music, Documentary, Free TV, NFL, College Football, NBA, NHL, MLB, Soccer, Fights & PPV, ESPN+, Sports Events, 24/7 Channels, Replays, Adult, Cameras. Foreign-language groups are left out.
- **Matches channels to the guide.**

Your choices live in `~/.config/onn-vanilla/lineup.json` (copied from [`lineup.full.example.json`](server/dispatcharr/lineup.full.example.json)). Each group is a list of regexes matched against your providers' group names. Remove a group, rename one, change its channel number, or set `"include"` to your city and call letters for Locals.

**When you add or change a provider:** add it in Dispatcharr, let it load, then run `./refresh-lineup.sh` or wait for the nightly run. New channels are merged in, dead ones are removed, and channel numbers stay put. Use `./refresh-lineup.sh --dry-run` to preview changes.

**Other countries and what's hidden:** only your country's channels are kept (`"countries": "US|USA"` in the lineup file). Add another country (the plugin's **Countries to keep**, e.g. `US|USA|UK|GB`) to get, say, the UK's sports, sports events and TV as their own groups, including their 4K sports feeds. 4K/UHD channels get their own **4K** group. Empty event slots (`NBA 12: NO EVENT`) are kept by default, because providers rename them when a game is on; set `"drop_empty_events": true` to drop them. To see everything left out, use the plugin's **What's left out** and paste any group into **Add back**, or run `./refresh-lineup.sh --hidden` on the server. Adding or dropping a provider needs nothing else: the same rules apply to the new provider's groups at the next refresh.

**Cameras and other custom streams** (RTSP/RTSPS cameras, HLS links): in Dispatcharr, add a custom stream, put it in a group named **Cameras**, and choose the **ffmpeg** stream profile. The next refresh adds it to the guide.

### 3. TV boxes

Open the wizard (`http://<server>:8765`), or download `onn-vanilla-setup-<your OS>` from [Releases](../../releases) and run it on any computer, which opens the same wizard in your browser:

1. **Live TV source:** your Dispatcharr address, and a test that the playlist loads.
2. **Home screen:** your ZIP code (weather, radar and local news), theme, accent, clock and greeting.
3. **Sports:** leagues and favorite teams.
4. **Set up a box:** the wizard walks you through turning on **Wireless debugging** (Android 11+/Google TV) and pairing with the code on screen, or USB debugging on older boxes. Click **Inspect**, tick the apps to remove, run a **Dry run**, then **Set up this box**.

The setup app downloads Google's `adb` itself if you don't have it. Run by hand on a headless machine, use `--listen 0.0.0.0` so other computers can open it, and `--data <folder>` to choose where settings and the password are kept.

### Live TV appearance

Each TV keeps its own look: **Live TV → Settings** has screen size, text size, guide row height, 2/3/4-hour guide, channel logos, accent color, background, adult channels, empty event slots and the score bar.

## What gets removed

Only what you tick in the wizard. The list is in [`setup/bloat.txt`](setup/bloat.txt): stock launchers, Assistant, ambient mode and preloaded streaming apps. Apps are removed with `pm uninstall --user 0`, which keeps the system copy, so **Restore removed apps** in the wizard brings every one back. Settings, Bluetooth remote pairing, HDMI-CEC, Wi-Fi, display/audio settings and codecs are never touched. The Play Store stays unless you choose otherwise, so you can still install apps (Silo, Kodi, etc.) and add them to Home with the **+** tile.

## Building

Requires JDK 17, the Android SDK, Gradle 8.9 and Go 1.22.

```bash
./scripts/build-all.sh      # APKs + setup binaries for every OS -> dist/
```

Release APKs are signed with `keystore.properties` if present, otherwise with the debug key. Pushing a `v*` tag builds everything on GitHub Actions and attaches it to a release. Add `KEYSTORE_B64`, `KEYSTORE_PASSWORD` and `KEY_ALIAS` secrets to sign with your own key.

Power users can skip the wizard: `provision/provision-onn.sh <box-ip> --dry-run` does the same from a shell (copy `provision.conf` to `provision.local.conf` first).

## Layout

| Path | What |
|---|---|
| `launcher/` | Vanilla Home (Kotlin, Compose for TV) |
| `livetv/` | Live TV (Kotlin, Compose, Media3) |
| `setup/` | Setup app (Go, single binary, embedded web wizard) |
| `provision/` | Shell provisioning + restore scripts |
| `server/` | Dispatcharr compose file and an example shared launcher config |

## License

MIT. Sports data comes from ESPN's public scoreboard API, weather from Open-Meteo, and geocoding from Open-Meteo and Zippopotam.us. This project is not affiliated with any of them, or with Onn/Walmart or Google.
