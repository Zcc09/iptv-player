# Google Play listing — Internet TV Player

Copy/paste these into Play Console when creating the app (Play Console → Create app → then
"Main store listing"). Character limits are Google's; the counts are verified below.

---

## App name (max 30)

```
Internet TV Player
```

## Short description (max 80)

```
Play your own M3U or Xtream IPTV playlists on phone, TV, and Chromecast.
```
(74 characters)

## Full description (max 4000)

```
Internet TV Player plays the IPTV you already subscribe to — load your own playlist and watch on
your phone, or on the big screen through Chromecast and Android TV.

It is a player, not a content service: there are no channels, subscriptions, or accounts bundled
in. You bring an M3U playlist link or an Xtream Codes account from your own provider.

BUILT FOR REAL IPTV SERVERS
• M3U / M3U-Plus playlist links, or Xtream Codes (XC) server with username and password
• Channel list with categories, search, and favourites
• Keeps working with Xtream servers that advertise MPEG-TS or HLS output
• Handles live MPEG-TS, HLS, fragmented MP4, MKV and DASH streams

WATCH IT YOUR WAY
• Full player controls: play/pause, previous/next channel, aspect ratio (fit / crop / stretch),
  picture-in-picture, and automatic retry on stream errors
• Channel logos and per-playlist caching, so the list is there the moment you open the app
• Your playlists refresh themselves in the background (15 minutes to 24 hours, or off) so the
  channel list stays current

CAST TO YOUR TV
• Cast to Chromecast, Google TV and Android TV, with a built-in HLS relay on your phone so that
  raw MPEG-TS streams — which Chromecast cannot play directly — work too
• Choose Auto, Relay or Direct casting depending on what your provider serves

MADE FOR THE LIVING ROOM
• A proper ten-foot Android TV interface: navigate with a remote or a gamepad, with a spotlight
  preview of the focused channel, a channel grid, and an in-player mini guide
• Channel zapping straight from the remote's channel buttons
• Switches to the TV interface automatically on a widescreen or TV device, or when you press a
  d-pad or gamepad button — and you can force either layout in Settings
• Optional on-screen button hints you can hide once you know your way around

PRIVATE BY DESIGN
• No accounts, no ads, no analytics, and no tracking
• Your playlists and credentials never leave your device — they are sent only to the server you
  configured
• Open source: https://github.com/Zcc09/iptv-player

REQUIREMENTS
• Android 8.0 or newer
• A playlist or Xtream account from an IPTV provider you have the right to use
• Casting needs a Chromecast or Android TV device on the same Wi-Fi network

Download it, point it at your own playlist, and watch.
```

## Category & tags

- **Application type:** Application (not a game)
- **Category:** Video Players & Editors
- **Tags:** video player, IPTV, casting / Chromecast

## Content rating questionnaire — expected answers

- Violence / sexuality / language / drugs / gambling: **No** to all
- "Does the app allow users to share or stream content?" — the app plays streams **the user
  supplies**; it hosts and provides no content of its own
- Expected outcome: everyone / PEGI 3-style rating. Answer the questionnaire honestly in the
  console; these are the facts it will ask about.

## Data safety form — expected answers

- **Does your app collect or share any required user data types?** → **No**
- **Is all user data encrypted in transit?** → Yes (streams and playlists are fetched over TLS
  when the provider's URL is https)
- **Do you provide a way for users to request data deletion?** → Not applicable — no data is
  collected or stored off-device. Playlists/settings are local and are removed by uninstalling.
- **Privacy policy URL:** https://zcc09.github.io/iptv-player/privacy-policy.html

## Graphic assets (already generated)

| Asset | Requirement | File |
|---|---|---|
| App icon | 512×512 PNG, 32-bit | `play/icon-512.png` |
| Feature graphic | 1024×500 PNG | `play/feature-graphic-1024x500.png` |
| TV banner | 320×180 | `app/src/main/res/drawable/tv_banner.xml` (400×180 asset needed if TV distribution is enabled) |
| Phone screenshots | 2–8, min 320px | capture from the app (see below) |

Regenerate the two PNGs any time with:

```bash
python tools/make_play_assets.py
```

## Screenshots

Not yet captured. Suggested set (needs a real device or emulator, phone layout):

1. Playlist list — "Your playlists, M3U or Xtream"
2. Channel list with categories — "Browse every channel"
3. Player with a live stream — "Watch live"
4. Cast dialog — "Cast to Chromecast or Android TV"
5. TV home (landscape) — "Built for the big screen"
6. Settings — interface mode and updates

If Android TV distribution is enabled, Play also requires TV screenshots (landscape, 1920×1080) and
a TV banner.

## Release checklist (console side)

- [ ] Developer account verified (identity verification is required for new accounts)
- [ ] App created with package name `com.zcc09.iptvplayer`
- [ ] Play App Signing enabled (Play holds the app signing key; CI keeps the upload key)
- [ ] Upload `internet-tv-player-play.aab` from the CI artifacts
- [ ] Store listing filled from this file
- [ ] Data safety + content rating + target audience completed
- [ ] Internal testing track first, then closed testing (see the 12-tester rule)
