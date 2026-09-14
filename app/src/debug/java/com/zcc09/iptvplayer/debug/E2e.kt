package com.zcc09.iptvplayer.debug

import android.app.Activity
import com.zcc09.iptvplayer.cast.CastCtl
import com.zcc09.iptvplayer.core.Channel
import com.zcc09.iptvplayer.core.E2eHandler
import com.zcc09.iptvplayer.core.Http
import com.zcc09.iptvplayer.core.Logx
import com.zcc09.iptvplayer.core.Playlist
import com.zcc09.iptvplayer.core.PlaylistType
import com.zcc09.iptvplayer.core.RelayManager
import com.zcc09.iptvplayer.core.Repo
import com.zcc09.iptvplayer.core.XtreamApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only driver used by the CI end-to-end job:
 *
 *   adb shell am start -n com.zcc09.iptvplayer/.MainActivity -e e2e <action>
 *
 * Everything it logs goes to the `IPTV_E2E` logcat tag. It is not compiled into
 * release builds (debug source set only) and the pack URL/credentials below are
 * the test server's.
 */
object E2e : E2eHandler {

    private const val M3U_URL = "https://tv.mojangle.net/output/m3u"
    private const val XC_URL = "https://tv.mojangle.net/output/m3u"
    private const val XC_USER = "Family"
    private const val XC_PASS = "123"

    /** Small, low-bitrate HLS stream used to prove real decode + render on an emulator. */
    private const val TEST_HLS =
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"

    private const val M3U_ID = "ci-m3u"
    private const val XC_ID = "ci-xc"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun handle(activity: Activity, action: String, extras: Map<String, String>) {
        Logx.i("E2E_ACTION $action extras=$extras")
        when (action.lowercase()) {
            "seed" -> scope.launch { seed() }
            "refresh" -> scope.launch {
                val total = Repo.refreshAll()
                Logx.i("E2E_REFRESH_DONE channels=$total")
            }

            "dump" -> dump()
            "xc" -> scope.launch { probeXtream() }
            "playfirst" -> playFirst()
            "playtest" -> playTestStream()
            "relay" -> scope.launch { relayProbe() }
            "playrelay" -> scope.launch { playRelay() }
            "probe" -> Logx.i("E2E_RELAY_STATUS ${RelayManager.status()}")
            "stoprelay" -> {
                RelayManager.stop()
                Logx.i("E2E_RELAY_STOPPED")
            }

            "casttest" -> castProbe(activity)
            else -> Logx.w("E2E_UNKNOWN_ACTION $action")
        }
    }

    private suspend fun seed() {
        try {
            for (existing in Repo.playlists.value.filter { it.name.startsWith("CI ") }) {
                Repo.delete(existing.id)
            }
            val m3u = Playlist(
                id = M3U_ID,
                name = "CI M3U playlist",
                type = PlaylistType.M3U,
                url = M3U_URL,
                autoRefresh = true,
                refreshIntervalMinutes = 15
            )
            val xc = Playlist(
                id = XC_ID,
                name = "CI Xtream account",
                type = PlaylistType.XTREAM,
                url = XC_URL,
                username = XC_USER,
                password = XC_PASS,
                autoRefresh = true,
                refreshIntervalMinutes = 15
            )
            Repo.add(m3u)
            Repo.add(xc)

            val m3uResult = Repo.refresh(m3u.id)
            val xcResult = Repo.refresh(xc.id)

            Logx.i(
                "SEED_OK m3u=${m3uResult.channels.size} m3uErr=${m3uResult.error ?: "-"} " +
                    "xc=${xcResult.channels.size} xcErr=${xcResult.error ?: "-"}"
            )
            m3uResult.channels.firstOrNull()?.let {
                Logx.i("SEED_M3U_FIRST name=${it.name} group=${it.group} url=${it.url}")
            }
            xcResult.channels.firstOrNull()?.let {
                Logx.i("SEED_XC_FIRST name=${it.name} group=${it.group} url=${it.url}")
            }
            Logx.i("SEED_STATE playlists=${Repo.playlists.value.size}")
        } catch (t: Throwable) {
            Logx.e("SEED_FAILED", t)
        }
    }

    private fun dump() {
        val all = Repo.playlists.value
        Logx.i("E2E_DUMP playlists=${all.size}")
        for (p in all) {
            val channels = Repo.channelsOf(p.id)
            Logx.i(
                "E2E_PLAYLIST name=${p.name} type=${p.type} channels=${channels.size} " +
                    "lastRefresh=${p.lastRefresh} lastRefreshOk=${p.lastRefreshOk} " +
                    "autoRefresh=${p.autoRefresh} every=${p.refreshIntervalMinutes}m " +
                    "err=${p.lastError.take(90)}"
            )
            channels.take(3).forEach { Logx.i("E2E_CHANNEL ${it.name} | ${it.group} | ${it.url}") }
        }
    }

    private suspend fun probeXtream() {
        try {
            val api = XtreamApi(XC_URL, XC_USER, XC_PASS, Repo.userAgent)
            Logx.i("XC_BASE ${api.base}")
            val auth = api.auth()
            val categories = api.liveCategories()
            val streams = api.liveStreams()
            Logx.i(
                "XC_API_OK base=${api.base} auth=${auth.ok} status=${auth.status} " +
                    "formats=${auth.formats} categories=${categories.size} streams=${streams.size} " +
                    "ext=${api.preferredExtension(auth.formats)}"
            )
            streams.firstOrNull()?.let {
                Logx.i("XC_FIRST_STREAM ${it.name} -> ${api.streamUrl(it, api.preferredExtension(auth.formats))}")
            }
        } catch (t: Throwable) {
            Logx.e("XC_API_FAILED", t)
        }
    }

    private fun firstChannel(): Channel? {
        val preferred = Repo.playlists.value.firstOrNull { it.id == M3U_ID }
            ?: Repo.playlists.value.firstOrNull()
        return preferred?.let { Repo.channelsOf(it.id).firstOrNull() }
    }

    private fun playFirst() {
        val channel = firstChannel()
        if (channel == null) {
            Logx.w("E2E_NO_CHANNEL_TO_PLAY")
            return
        }
        Logx.i("E2E_PLAY_REQUEST ${channel.name} ${channel.url}")
        Repo.requestPlay(channel)
    }

    private fun playTestStream() {
        val channel = Channel(
            id = "ci-test-hls",
            playlistId = "ci-test",
            name = "CI HLS test stream",
            url = TEST_HLS
        )
        Logx.i("E2E_PLAY_REQUEST test ${channel.name} ${channel.url}")
        Repo.requestPlay(channel)
    }

    /** Start the HLS relay, then validate its output over HTTP from inside the app. */
    private suspend fun relayProbe() {
        val channel = firstChannel()
        if (channel == null) {
            Logx.w("E2E_RELAY_NO_CHANNEL")
            return
        }
        val lanUrl = RelayManager.ensure(channel, Repo.userAgent)
        Logx.i("E2E_RELAY_URL $lanUrl")
        delay(18_000)
        Logx.i("E2E_RELAY_STATUS ${RelayManager.status()}")

        val local = RelayManager.localUrl()
        if (local == null) {
            Logx.w("E2E_RELAY_NO_LOCAL_URL")
            return
        }
        try {
            val playlist = Http.getText(local, timeoutMs = 30_000)
            Logx.i("E2E_RELAY_PLAYLIST ${playlist.replace("\n", " | ").take(300)}")
            val segmentLine = playlist.lines().firstOrNull { it.trim().endsWith(".ts") }
            if (segmentLine == null) {
                Logx.w("E2E_RELAY_NO_SEGMENT_IN_PLAYLIST")
                return
            }
            val segmentUrl = local.replace("live.m3u8", segmentLine.trim())
            val data = Http.getBytes(segmentUrl, timeoutMs = 30_000)
            val aligned = data.size > 188 * 4 &&
                data[0] == 0x47.toByte() &&
                data[188] == 0x47.toByte() &&
                data[376] == 0x47.toByte()
            Logx.i("E2E_RELAY_SEGMENT bytes=${data.size} tsAligned=$aligned")
        } catch (t: Throwable) {
            Logx.e("E2E_RELAY_FETCH_FAILED", t)
        }
    }

    /** Play the relay's own HLS output through the normal player path. */
    private suspend fun playRelay() {
        val channel = firstChannel()
        if (channel == null) {
            Logx.w("E2E_RELAY_NO_CHANNEL")
            return
        }
        RelayManager.ensure(channel, Repo.userAgent)
        val local = RelayManager.localUrl()
        if (local == null) {
            Logx.w("E2E_RELAY_NO_LOCAL_URL")
            return
        }
        // Give the relay a couple of segments so the player can start immediately.
        delay(12_000)
        Logx.i("E2E_PLAY_REQUEST relay $local")
        Repo.requestPlay(channel, local)
    }

    private fun castProbe(activity: Activity) {
        Logx.i("CAST_AVAILABLE ${CastCtl.available}")
        try {
            CastCtl.startDiscovery(activity)
            Logx.i("CAST_ROUTES ${CastCtl.routes.value.size}")
        } catch (t: Throwable) {
            Logx.w("CAST_PROBE_FAILED ${t.message}")
        }
    }
}
