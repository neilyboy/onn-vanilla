# Onn Vanilla

Turn an Onn (or any Android TV / Google TV) box into a plain, fast TV appliance:

- **Vanilla Home**: a minimal launcher with a clock, weather, a 7-day forecast, six plain themes and only the apps you choose. Add, move or remove apps from the TV itself (long-press a tile).
- **Live TV**: a free, open-source IPTV player built for game day. It shows live and upcoming games for your leagues, gives your teams the big card, and jumps straight to the channel carrying the game. It also has a full TV guide. Built on AndroidX Media3 (ExoPlayer).
- **Setup app**: a small program for Windows, macOS or Linux. It opens a wizard in your browser, then connects to the box over Wi-Fi and installs everything for you.
- **Dispatcharr** backend: merges all your IPTV providers into **one** playlist and guide, respects each provider's stream limit, and shares one upstream stream when several boxes watch the same channel.

Nothing personal ships in this repo. The setup app keeps your settings on your computer only.

## Quick start

### 1. Server (once)

On any always-on Linux machine:

```sh
git clone https://github.com/neilyboy/onn-vanilla && cd onn-vanilla/server && ./install.sh
```

This installs Docker if needed, starts Dispatcharr on port 9191, asks for your local TV market, and schedules a nightly channel-list refresh. Re-running it on a new server rebuilds everything the same way. Then:

1. Open `http://<server>:9191` and create your admin login.
2. **M3U & EPG Manager → Add M3U → Xtream Codes**, once per provider (server URL, username, password). Wait for each to finish loading.
3. Build the channel list: `./refresh-lineup.sh` (or **Build my channel list** in the setup app).

### 2. Your channel list

Providers list the same channel many times (once per provider, plus HD/FHD/backup copies) and mix in event slots and foreign groups. The lineup builder ([`server/dispatcharr/curate.py`](server/dispatcharr/curate.py)) turns that into one tidy guide:

- **Merges duplicates** of linear channels (ESPN, CNN, locals…) across providers by guide ID or name. Each merged channel keeps every copy as a backup, best provider first. If a provider is down or at its limit, Dispatcharr switches to the next.
- **Reads each provider's real stream limit** from its Xtream account and sets it in Dispatcharr.
- **Sorts everything into groups**, sports first: Locals, Sports, News, Entertainment, Movies, Kids, Music, Documentary, Free TV, NFL, College Football, NBA, NHL, MLB, Soccer, Fights & PPV, ESPN+, Sports Events, 24/7 Channels, Replays, Adult, Cameras. Foreign-language groups are left out.
- **Matches channels to the guide.**

Your choices live in `~/.config/onn-vanilla/lineup.json` (copied from [`lineup.full.example.json`](server/dispatcharr/lineup.full.example.json)). Each group is a list of regexes matched against your providers' group names. Remove a group, rename one, change its channel number, or set `"include"` to your city and call letters for Locals.

**When you add or change a provider:** add it in Dispatcharr, let it load, then run `./refresh-lineup.sh` or wait for the nightly run. New channels are merged in, dead ones are removed, and channel numbers stay put. Use `./refresh-lineup.sh --dry-run` to preview changes.

**Cameras and other custom streams** (RTSP/RTSPS cameras, HLS links): in Dispatcharr, add a custom stream, put it in a group named **Cameras**, and choose the **ffmpeg** stream profile. The next refresh adds it to the guide.

### 3. TV boxes

Download `onn-vanilla-setup-<your OS>` from [Releases](../../releases) and run it. Your browser opens the wizard:

1. **Live TV source:** your Dispatcharr address. Test the playlist, or build the channel list from here.
2. **Home screen:** weather location (ZIP or city), theme, accent, clock and greeting.
3. **Sports:** leagues and favorite teams.
4. **Set up a box:** the wizard walks you through turning on **Wireless debugging** (Android 11+/Google TV) and pairing with the code on screen, or USB debugging on older boxes. Click **Inspect**, tick the apps to remove, run a **Dry run**, then **Set up this box**.

The setup app downloads Google's `adb` itself if you don't have it. To run it on a headless server and open it from another computer, use `--listen 0.0.0.0`. It prints a link that contains a one-time access token.

### Live TV appearance

Each TV keeps its own look: **Live TV → Settings** has screen size (80–140%, for 1080p vs 4K), text size, guide row height, 2/3/4-hour guide, channel logos, accent color and background.

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
