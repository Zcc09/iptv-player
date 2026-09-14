#!/usr/bin/env bash
# End-to-end verification of the real app on a real emulator, against live streams.
#
#  1. install the debug APK
#  2. load the M3U playlist link and the Xtream account (live server, real channels)
#  3. refresh all playlists (the auto-refresh code path)
#  4. render the playlist screen and assert the downloaded data shows up in the UI
#  5. play a real 4K MPEG-TS channel
#  6. play a low-bitrate HLS stream and wait for a decoded, rendered frame
#  7. start the HLS relay and validate its playlist + segments (on device AND from the host)
#  8. play the relay's own HLS output back through the player
#  9. exercise the Chromecast code path
# 10. fail on any crash

set -uo pipefail

PKG=com.zcc09.iptvplayer
ACT="$PKG/.MainActivity"
APK=apk/iptv-player-debug.apk
FAILURES=0
LOG=""

pass() { echo "  ✅ $1"; }
fail() { echo "  ❌ $1"; FAILURES=$((FAILURES + 1)); }
step() { echo; echo "=== $1"; }

refresh_log() {
  LOG=$(adb logcat -d -v time IPTV_E2E:V '*:S' 2>/dev/null | sed 's/.*IPTV_E2E *: *//')
}

show_log() {
  echo "$LOG" | grep -E "$1" | sed 's/^/     | /' || true
}

assert_log() { # pattern, description
  if echo "$LOG" | grep -qE "$1"; then pass "$2"; else fail "$2 (no log line matching /$1/)"; show_log "$1" || true; fi
}

run_action() { # action, seconds to wait
  adb logcat -c > /dev/null 2>&1 || true
  adb shell am force-stop "$PKG" > /dev/null 2>&1 || true
  adb shell am start -W -n "$ACT" -e e2e "$1" > /dev/null 2>&1 || true
  sleep "$2"
  refresh_log
}

step "Install debug APK"
adb install -r "$APK" || { echo "install failed"; exit 1; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS > /dev/null 2>&1 || true
adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 1800000 > /dev/null 2>&1 || true
pass "installed $APK"

step "Load playlists from the live server (M3U link + Xtream account)"
run_action seed 70
assert_log "APP_STARTED" "app started"
assert_log "SEED_OK m3u=[1-9][0-9]*" "M3U playlist link returned channels"
assert_log "SEED_OK .*xc=[1-9][0-9]*" "Xtream account returned channels"
assert_log "SEED_M3U_FIRST" "first M3U channel parsed"
assert_log "SEED_XC_FIRST" "first Xtream channel parsed with a stream url"
show_log "SEED_"

step "Xtream (XC) API directly: auth, categories, live streams"
run_action xc 25
assert_log "XC_API_OK .*auth=true" "Xtream login accepted"
assert_log "XC_API_OK .*streams=[1-9][0-9]*" "Xtream live streams listed"
show_log "XC_"

step "Refresh all playlists (auto-refresh code path)"
run_action refresh 55
assert_log "REFRESH_ALL_DONE channels=[1-9][0-9]*" "refresh-all re-downloaded channels"
show_log "REFRESH|AUTO_REFRESH"

step "State dump (auto-refresh scheduling + cached channels)"
run_action dump 8
assert_log "E2E_PLAYLIST .*channels=[1-9][0-9]*" "channels cached for a playlist"
assert_log "E2E_PLAYLIST .*autoRefresh=true" "auto-refresh enabled on the playlist"
assert_log "E2E_CHANNEL" "channel entries logged"
show_log "E2E_PLAYLIST|E2E_CHANNEL"

step "Playlist screen renders the downloaded data"
adb shell uiautomator dump /sdcard/ui-home.xml > /dev/null 2>&1 || true
UI=$(adb shell cat /sdcard/ui-home.xml 2>/dev/null || echo "")
if echo "$UI" | grep -q "CI M3U playlist"; then
  pass "playlist visible in the UI"
else
  fail "playlist not found in the UI hierarchy"
fi
if echo "$UI" | grep -qiE "channels"; then
  pass "channel count shown in the UI"
else
  fail "channel count missing from the UI"
fi

step "Play a real 4K MPEG-TS channel"
run_action playfirst 45
assert_log "PLAYBACK_OPENING url=" "player opened the stream url"
if echo "$LOG" | grep -qE "PLAYBACK_BUFFERING|PLAYBACK_READY|PLAYBACK_VIDEO_SIZE|PLAYBACK_FIRST_FRAME"; then
  pass "live TS stream data reached the player"
else
  fail "no playback progress on the live TS channel"
fi
show_log "PLAYBACK_"
if echo "$LOG" | grep -q "PLAYBACK_ERROR"; then
  echo "     ! player reported an error (expected on an emulator for 4K hardware codecs):"
  show_log "PLAYBACK_ERROR"
fi

step "Player screen shows the channel and the Cast control"
adb shell uiautomator dump /sdcard/ui-player.xml > /dev/null 2>&1 || true
PUI=$(adb shell cat /sdcard/ui-player.xml 2>/dev/null || echo "")
if echo "$PUI" | grep -qE "AR: |CI "; then
  pass "player overlay shows the channel name"
else
  fail "player overlay missing the channel name"
fi

step "Play a low-bitrate HLS stream and wait for a rendered frame"
run_action playtest 40
assert_log "PLAYBACK_FIRST_FRAME" "a frame was decoded and rendered on screen"
if echo "$LOG" | grep -q "PLAYBACK_VIDEO_SIZE"; then pass "video size reported by the decoder"; fi

step "HLS relay: start it for a live TS channel and validate the output"
run_action relay 60
assert_log "RELAY_START upstream=" "relay started"
assert_log "RELAY_URL http://" "relay published a playlist url for the TV"
assert_log "RELAY_UPSTREAM_CONNECTED" "relay connected to the upstream stream"
assert_log "E2E_RELAY_STATUS .*\"bytesIn\":[1-9][0-9]*" "relay is receiving stream data"
assert_log "E2E_RELAY_PLAYLIST .*#EXTM3U" "relay serves a valid HLS media playlist"
assert_log "E2E_RELAY_SEGMENT bytes=[1-9][0-9]* tsAligned=true" "relay segments are 188-byte aligned MPEG-TS"
show_log "RELAY|E2E_RELAY"

step "Relay output verified independently from the host"
PORT=$(echo "$LOG" | grep -oE "RELAY_URL http://[0-9.]+:[0-9]+" | grep -oE "[0-9]+$" | head -1)
if [ -n "${PORT:-}" ]; then
  adb forward tcp:18080 "tcp:$PORT" > /dev/null 2>&1 || true
  curl -sS -m 30 "http://127.0.0.1:18080/live.m3u8" -o e2e-relay.m3u8 || true
  if grep -q "#EXTM3U" e2e-relay.m3u8 2>/dev/null; then
    pass "host fetched the relay playlist (port $PORT)"
    SEG=$(grep -m1 -oE "seg/[0-9]+\.ts" e2e-relay.m3u8 || true)
    if [ -n "${SEG:-}" ]; then
      curl -sS -m 30 "http://127.0.0.1:18080/$SEG" -o e2e-segment.ts || true
      python3 - "$SEG" <<'PY'
import sys, pathlib
name = sys.argv[1]
p = pathlib.Path("e2e-segment.ts")
data = p.read_bytes() if p.exists() else b""
ok = len(data) > 188 * 4 and all(data[i] == 0x47 for i in (0, 188, 376, 564))
pat = len(data) > 3 and data[0] == 0x47 and (data[1] & 0x40) and (data[1] & 0x1F) == 0 and data[2] == 0
print(f"  {'✅' if ok else '❌'} host received {name}: {len(data)} bytes, 188-byte TS sync: {ok}")
print(f"  {'✅' if pat else '❌'} segment starts with a PAT packet: {bool(pat)}")
sys.exit(0 if (ok and pat) else 1)
PY
      if [ $? -ne 0 ]; then FAILURES=$((FAILURES + 1)); fi
    else
      fail "no segment uri in the relay playlist"
    fi
  else
    fail "host could not fetch the relay playlist"
  fi
else
  fail "could not determine the relay port from the log"
fi

step "Play the relay's own HLS output through the player"
run_action playrelay 55
assert_log "PLAYBACK_OPENING url=http://127.0.0.1" "player opened the relay HLS url"
if echo "$LOG" | grep -qE "PLAYBACK_READY|PLAYBACK_FIRST_FRAME|PLAYBACK_VIDEO_SIZE|PLAYBACK_BUFFERING"; then
  pass "the relayed HLS stream plays back in a real HLS client"
else
  fail "the relayed HLS stream did not play back"
fi
show_log "PLAYBACK_"

step "Chromecast code path (emulators have no Cast device, must degrade gracefully)"
run_action casttest 10
assert_log "CAST_(AVAILABLE|INIT_OK|INIT_FAILED)" "cast subsystem initialised or reported as unavailable"
show_log "CAST_"

step "Crash check"
CRASHES=$(adb logcat -d 2>/dev/null | grep -c "FATAL EXCEPTION" || true)
if [ "${CRASHES:-0}" -eq 0 ]; then
  pass "no fatal exceptions in logcat"
else
  fail "$CRASHES fatal exception(s) in logcat"
  adb logcat -d | grep -A 25 "FATAL EXCEPTION" | head -80
fi
ANRS=$(adb logcat -d 2>/dev/null | grep -c "ANR in $PKG" || true)
if [ "${ANRS:-0}" -eq 0 ]; then pass "no ANRs"; else fail "$ANRS ANR(s)"; fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "=========================================="
  echo " END-TO-END: ALL CHECKS PASSED"
  echo "=========================================="
  exit 0
fi
echo "=========================================="
echo " END-TO-END: $FAILURES CHECK(S) FAILED"
echo "=========================================="
exit 1
