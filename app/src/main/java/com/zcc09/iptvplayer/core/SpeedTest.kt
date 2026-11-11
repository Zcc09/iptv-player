package com.zcc09.iptvplayer.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Measures the link between this device and one provider server.
 *
 * Two things matter to an IPTV viewer and they fail differently: **latency**
 * (how long the server takes to start answering, which is what makes zapping
 * feel slow) and **throughput** (whether a 4K channel will play without
 * stalling). So the test reports both, and it measures the download of a real
 * stream rather than a speed-test file, because the path that matters is the
 * one the video takes.
 */
data class SpeedResult(
    val latencyMs: Long,
    val mbps: Double,
    val bytes: Long,
    val seconds: Double,
    val url: String,
    val error: String = ""
) {
    val ok: Boolean get() = error.isEmpty() && bytes > 0
}

object SpeedTest {

    /** How long to sample. Long enough to fill the pipe, short enough to wait for. */
    const val SAMPLE_MS = 8000L

    /** A rough ceiling, so a fast line cannot spin forever on a huge file. */
    private const val MAX_BYTES = 64L * 1024 * 1024

    suspend fun run(url: String, sampleMs: Long = SAMPLE_MS): SpeedResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (url.isBlank()) {
                return@withContext SpeedResult(0, 0.0, 0, 0.0, url, "No url")
            }
            var conn: HttpURLConnection? = null
            try {
                val started = System.nanoTime()
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", Repo.userAgent)
                    setRequestProperty("Accept", "*/*")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext SpeedResult(0, 0.0, 0, 0.0, url, "HTTP $code")
                }
                val latencyMs = (System.nanoTime() - started) / 1_000_000
                val deadline = started + sampleMs * 1_000_000
                var total = 0L
                val buf = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    while (System.nanoTime() < deadline && total < MAX_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                }
                val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                val mbps = if (seconds > 0) (total * 8.0) / (seconds * 1_000_000.0) else 0.0
                Logx.i("SPEEDTEST bytes=$total seconds=$seconds mbps=${"%.2f".format(mbps)}")
                SpeedResult(latencyMs, mbps, total, seconds, url)
            } catch (t: Throwable) {
                SpeedResult(0, 0.0, 0, 0.0, url, t.message?.take(90) ?: "Failed")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    /** Human line for the settings row: "12.4 Mbps · 85 ms · 6.2 MB in 5.0 s". */
    fun describe(r: SpeedResult): String = when {
        !r.ok -> "Speed test failed: ${r.error.ifBlank { "no data" }}"
        else -> "${formatMbps(r.mbps)} Mbps  ·  ${r.latencyMs} ms  ·  " +
            "${formatSize(r.bytes)} in ${"%.1f".format(r.seconds)} s"
    }

    /** One decimal under 100 Mbps, none above - nobody needs 142.37. */
    fun formatMbps(mbps: Double): String =
        if (mbps >= 100) "%.0f".format(mbps) else "%.1f".format(mbps)

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * A stream url for a playlist: its first channel when one is cached, else
     * the playlist document itself (still proves reachability and throughput).
     */
    fun targetFor(playlist: Playlist): String {
        val channel = Repo.channelsOf(playlist.id).firstOrNull { it.url.isNotBlank() }
        return channel?.url ?: playlist.url
    }
}
