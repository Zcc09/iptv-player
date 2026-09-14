# IPTV Player (Android)

A small, dependency-light Android IPTV player: load a **playlist from an M3U link** or an
**Xtream Codes (XC) server** with username/password, browse the channel list, watch it in a
built-in player, **cast it to a TV**, and let the playlist **refresh itself** in the background.

Built with Kotlin + Jetpack Compose + Media3/ExoPlayer. No ads, no analytics, no accounts.

## Features

| | |
|---|---|
| **Sources** | M3U / M3U-Plus playlist URL, or Xtream Codes API (`player_api.php`) with username + password |
| **Channel list** | Group/category filter, search, favourites, channel logos, per-playlist cache |
| **Player** | Media3/ExoPlayer: MPEG-TS, HLS, fMP4, MKV, DASH; fit/crop/stretch aspect modes, previous/next channel, retry on error, picture-in-picture |
| **Casting** | Chromecast / Google TV / Android TV via the Cast SDK (default media receiver) **plus a built-in HLS relay** so raw MPEG-TS channels can actually be cast |
| **Auto refresh** | WorkManager job per playlist (15 min … 24 h, or off) + on-launch refresh when the interval has elapsed + manual "refresh all" |
| **Android TV** | Declares a leanback launcher entry, so the APK can be installed directly on a TV box as an alternative to casting |

## Install

Grab `iptv-player-release.apk` from the [latest release](../../releases/latest) and open it on
the phone (allow "install unknown apps" when prompted). Android 8.0 (API 26) or newer.
The APK is signed with a stable key, so later releases install straight over it.

## Casting, and why this app ships an HLS relay

Chromecast's *default media receiver* plays **HLS / DASH / MP4 / WebM** — but **not raw
MPEG-TS**, which is exactly what most IPTV providers hand out (`.../live/user/pass/1.ts`, or
extension-less proxy URLs like `<host>/proxy/ts/stream/<uuid>`).

So the app has three cast modes (Settings → Casting):

* **Auto** (default) — HLS sources are sent to the TV as-is; anything else is re-published by
  the phone as HLS first.
* **Relay** — always re-publish from the phone as HLS.
* **Direct** — hand the raw URL to the TV (only useful for providers that serve HLS, or for
  panels whose `.mp4` output the TV can play).

The relay is a tiny HTTP server on the phone (`http://<phone-ip>:<port>/live.m3u8`) that cuts the
live MPEG-TS byte stream into ~3 s HLS segments at PAT boundaries — **no re-encoding**, so audio
and video stay in sync and CPU use is negligible. The TV pulls the playlist and segments from the
phone, so the phone and the TV must be on the same Wi-Fi. The phone can go to sleep only if you
leave the player screen; keeping the app open (or the screen on) keeps the relay fed.

> Casting needs Google Play Services. On a device without it the cast button is disabled and
> local playback works exactly as before.

## Auto refresh

Each playlist has its own interval (15 min, 30 min, 1 h, 3 h, 6 h, 12 h, 24 h) or can be switched
off. Refreshes run through WorkManager while the app is not in use, keep the previous channel list
if the download fails, and record the last error per playlist so you can see what went wrong.

## Build

```bash
gradle :app:assembleDebug        # or open the folder in Android Studio
gradle :app:testDebugUnitTest    # M3U parser, URL tools, TS->HLS segmenter
```

Toolchain: AGP 9.4.0, Gradle 9.7.1, Kotlin 2.4.20, compileSdk/targetSdk 36, minSdk 26.
Release signing comes from `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
env vars (see `.github/workflows/android.yml`); without them the release build falls back to the
debug key so forks still build.

## Continuous integration

`.github/workflows/android.yml` builds both APKs, runs the unit tests, publishes a GitHub release
on tags/manual dispatch, and runs a real **end-to-end job on an Android emulator**
(`.github/e2e.sh`) that installs the debug APK and then, against the live test server:

1. loads the M3U playlist link and the Xtream account and asserts real channels come back,
2. refreshes all playlists (the auto-refresh path) and asserts the cached channel counts,
3. asserts the playlist and channel count actually render in the UI,
4. plays a live 4K MPEG-TS channel and asserts stream data reaches the player,
5. plays a low-bitrate HLS stream and waits for a decoded, rendered frame,
6. starts the HLS relay, validates the playlist and a segment on-device *and* from the host
   (188-byte TS sync + PAT packet), then plays the relay's own HLS output back through the player,
7. exercises the Chromecast code path and asserts it degrades gracefully,
8. fails the build on any `FATAL EXCEPTION` or ANR.

## Layout

```
app/src/main/java/com/zcc09/iptvplayer/
├── core/      Models, HTTP, M3U parser, Xtream client, repository, WorkManager refresh, HLS relay
├── player/    ExoPlayer construction, container hints, cast URL selection
├── cast/      Cast SDK options + session/route control
└── ui/        Compose screens: playlists, playlist editor, channels, player, settings
app/src/debug/ Debug-only end-to-end driver (am start -e e2e <action>)
app/src/test/  JVM unit tests (no Android framework needed)
```

## Known limitations

* No EPG/programme guide yet (the playlist's `url-tvg` EPG URL is parsed and kept, but not shown).
* No VOD/series sections — live channels only.
* Casting is Chromecast (Cast SDK) only; DLNA/UPnP TVs are not supported yet.
* The relay is a pass-through segmenter: it does not transcode, so a TV that cannot decode the
  channel's codec (e.g. HEVC on an old Chromecast) still will not play it.
