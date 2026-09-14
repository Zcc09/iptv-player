package com.zcc09.iptvplayer.core

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Splits a continuous MPEG-TS byte stream into HLS segments.
 *
 * Segments are cut immediately *before* a PAT packet so every segment starts
 * with a programme table, and the byte target adapts to the measured bitrate so
 * each segment is roughly [targetSeconds] of video. Nothing is re-encoded: the
 * TS payload is relayed byte-for-byte, so audio/video stay in sync.
 *
 * Pure Kotlin on purpose - covered by plain JVM unit tests.
 */
class TsSegmenter(
    private val targetSeconds: Double = 3.0,
    private val maxSegments: Int = 6,
    private val initialTargetBytes: Int = 2_000_000,
    private val minSegmentBytes: Int = 64 * 1024
) {
    class Segment(val seq: Long, val data: ByteArray, val durationSec: Double)

    companion object {
        const val TS_PACKET = 188
        const val TARGET_DURATION = 6
        private const val MIN_TARGET_BYTES = 400_000
        private const val MAX_TARGET_BYTES = 12_000_000
    }

    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val segments = ArrayDeque<Segment>()
    private val buf = ByteArrayOutputStream(4 * 1024 * 1024)

    private var nextSeq = 1L
    private var targetBytes = initialTargetBytes
    private var totalBytes = 0L
    private var lastFlushBytes = 0L
    private var lastFlushAt = 0L
    private var produced = 0

    val producedSegments: Int get() = lock.withLock { produced }

    fun feed(chunk: ByteArray, len: Int) {
        if (len <= 0) return
        var off = 0
        while (off < len) {
            val remaining = len - off
            if (remaining < TS_PACKET) {
                buf.write(chunk, off, remaining)
                break
            }
            if (chunk[off] != 0x47.toByte()) {
                // Not packet aligned (shouldn't happen for TS) - resync.
                off++
                continue
            }
            if (buf.size() >= targetBytes && isPat(chunk, off)) {
                flush()
            }
            buf.write(chunk, off, TS_PACKET)
            off += TS_PACKET
        }
    }

    /** Flush whatever is buffered (called when the upstream stream ends). */
    fun finish() {
        if (buf.size() >= TS_PACKET) flush(force = true)
    }

    private fun isPat(chunk: ByteArray, off: Int): Boolean {
        if (off + 2 >= chunk.size) return false
        val b1 = chunk[off + 1].toInt() and 0xFF
        val b2 = chunk[off + 2].toInt() and 0xFF
        return (b1 and 0x40) != 0 && (b1 and 0x1F) == 0 && b2 == 0
    }

    private fun flush(force: Boolean = false) {
        val size = buf.size()
        if (size < TS_PACKET) return
        if (!force && size < minSegmentBytes) return

        val now = System.currentTimeMillis()
        if (lastFlushAt == 0L) lastFlushAt = now
        val data = buf.toByteArray()
        buf.reset()

        val elapsed = (now - lastFlushAt).coerceAtLeast(1L)
        val deltaBytes = totalBytes + data.size - lastFlushBytes
        val bps = (deltaBytes * 8.0) / (elapsed / 1000.0)
        totalBytes += data.size
        lastFlushAt = now
        lastFlushBytes = totalBytes

        val duration = if (bps > 50_000) {
            (data.size * 8.0) / bps
        } else {
            targetSeconds
        }
        if (bps > 100_000) {
            targetBytes = (bps * targetSeconds / 8.0).toInt().coerceIn(MIN_TARGET_BYTES, MAX_TARGET_BYTES)
        }

        val segment = Segment(
            seq = nextSeq++,
            data = data,
            durationSec = duration.coerceIn(0.5, TARGET_DURATION.toDouble())
        )
        lock.withLock {
            segments.addLast(segment)
            while (segments.size > maxSegments) segments.removeFirst()
            produced++
            available.signalAll()
        }
    }

    /** Wait until at least [count] segments are buffered (or the timeout expires). */
    fun awaitSegments(count: Int, timeoutMs: Long): List<Segment> {
        lock.withLock {
            var remaining = timeoutMs
            while (segments.size < count && remaining > 0) {
                val start = System.currentTimeMillis()
                try {
                    available.await(remaining, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                remaining -= (System.currentTimeMillis() - start)
            }
            return segments.toList()
        }
    }

    fun snapshot(): List<Segment> = lock.withLock { segments.toList() }

    fun segmentData(seq: Long): ByteArray? =
        lock.withLock { segments.firstOrNull { it.seq == seq }?.data }

    fun mediaPlaylist(): String {
        val list = snapshot()
        val sb = StringBuilder(512)
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:").append(TARGET_DURATION).append('\n')
        if (list.isNotEmpty()) {
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(list.first().seq).append('\n')
        }
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        for (s in list) {
            sb.append("#EXTINF:")
                .append(String.format(Locale.US, "%.3f", s.durationSec))
                .append(",\n")
            sb.append("seg/").append(s.seq).append(".ts\n")
        }
        return sb.toString()
    }
}

/**
 * Publishes a live MPEG-TS source as HLS over HTTP so devices that cannot play
 * raw TS (Chromecast's default receiver, many smart TVs) can play it.
 */
class HlsRelay {

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var bytesIn: Long = 0L
        private set

    @Volatile
    var clientsServed: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var upstreamCode: Int = 0
        private set

    @Volatile
    private var running = false

    private var server: ServerSocket? = null
    private var segmenter: TsSegmenter? = null
    private var upstreamUrl: String = ""
    private var headers: Map<String, String> = emptyMap()
    private var userAgent: String = Http.DEFAULT_UA

    val isRunning: Boolean get() = running

    fun start(
        upstream: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = Http.DEFAULT_UA
    ): Int {
        stop()
        val seg = TsSegmenter()
        segmenter = seg
        upstreamUrl = upstream
        this.headers = headers
        this.userAgent = userAgent
        bytesIn = 0L
        clientsServed = 0
        lastError = null
        upstreamCode = 0

        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(0))
        server = ss
        port = ss.localPort
        running = true

        thread(isDaemon = true, name = "relay-upstream") { readLoop(seg) }
        thread(isDaemon = true, name = "relay-http") { acceptLoop(ss, seg) }
        Logx.i("RELAY_START upstream=$upstream port=$port")
        return port
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        segmenter = null
    }

    fun playlistUrl(host: String = Net.lanIp() ?: "127.0.0.1"): String = "http://$host:$port/live.m3u8"

    fun statusJson(): String = buildString {
        append("{\"running\":").append(running)
        append(",\"port\":").append(port)
        append(",\"bytesIn\":").append(bytesIn)
        append(",\"clients\":").append(clientsServed)
        append(",\"segments\":").append(segmenter?.producedSegments ?: 0)
        append(",\"upstreamCode\":").append(upstreamCode)
        append(",\"error\":").append("\"").append((lastError ?: "").replace('"', '\'')).append("\"}")
    }

    // ------------------------------------------------------------------ threads

    private fun readLoop(seg: TsSegmenter) {
        var attempt = 0
        val buffer = ByteArray(64 * 1024)
        while (running) {
            attempt++
            try {
                val (conn, input) = Http.openStream(
                    upstreamUrl,
                    headers,
                    timeoutMs = 20000,
                    readTimeoutMs = 30000,
                    ua = userAgent
                )
                upstreamCode = conn.responseCode
                attempt = 0
                Logx.i("RELAY_UPSTREAM_CONNECTED code=$upstreamCode url=$upstreamUrl")
                while (running) {
                    val n = try {
                        input.read(buffer)
                    } catch (t: Throwable) {
                        Logx.w("RELAY_UPSTREAM_READ_END ${t.javaClass.simpleName}: ${t.message}")
                        -1
                    }
                    if (n < 0) break
                    if (n > 0) {
                        bytesIn += n
                        seg.feed(buffer, n)
                    }
                }
                runCatching { input.close() }
                runCatching { conn.disconnect() }
                seg.finish()
                if (running) Logx.w("RELAY_UPSTREAM_EOF bytes=$bytesIn")
            } catch (t: Throwable) {
                if (running) {
                    lastError = "${t.javaClass.simpleName}: ${t.message}"
                    Logx.w("RELAY_UPSTREAM_ERROR $lastError")
                }
            }
            if (running && attempt <= 30) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            } else if (running) {
                lastError = "giving up after $attempt reconnect attempts"
                return
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket, seg: TsSegmenter) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (t: Throwable) {
                if (running) Logx.w("RELAY_ACCEPT_END ${t.javaClass.simpleName}")
                return
            }
            thread(isDaemon = true, name = "relay-client") { serve(socket, seg) }
        }
    }

    private fun serve(socket: Socket, seg: TsSegmenter) {
        clientsServed++
        try {
            socket.soTimeout = 20000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val path = parts[1]
            val out = socket.getOutputStream()

            when {
                path.startsWith("/live.m3u8") || path.startsWith("/index.m3u8") -> {
                    val list = seg.awaitSegments(2, 25000)
                    if (list.isEmpty()) {
                        writeJson(out, 503, "{\"error\":\"relay warming up\"}", method == "HEAD")
                        return
                    }
                    val body = seg.mediaPlaylist().toByteArray(Charsets.UTF_8)
                    Logx.i("RELAY_PLAYLIST_HIT segments=${list.size} bytesIn=$bytesIn")
                    writeBody(out, 200, "application/vnd.apple.mpegurl", body, method == "HEAD")
                }

                path.startsWith("/seg/") -> {
                    val name = path.removePrefix("/seg/").substringBefore('?')
                    val seq = name.removeSuffix(".ts").toLongOrNull()
                    val data = if (seq != null) seg.segmentData(seq) else null
                    if (data == null) {
                        writeJson(out, 404, "{\"error\":\"segment not in window\"}", method == "HEAD")
                    } else {
                        Logx.i("RELAY_SEGMENT_HIT seq=$seq bytes=${data.size}")
                        writeBody(out, 200, "video/mp2t", data, method == "HEAD")
                    }
                }

                path.startsWith("/healthz") -> {
                    val body = statusJson().toByteArray(Charsets.UTF_8)
                    writeBody(out, 200, "application/json", body, method == "HEAD")
                }

                else -> writeJson(out, 404, "{\"error\":\"not found\"}", method == "HEAD")
            }
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun header(status: Int, type: String, length: Int): String = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        append("Content-Type: ").append(type).append("\r\n")
        append("Content-Length: ").append(length).append("\r\n")
        append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Headers: *\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    private fun writeBody(out: OutputStream, status: Int, type: String, body: ByteArray, headOnly: Boolean) {
        out.write(header(status, type, body.size).toByteArray(Charsets.US_ASCII))
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun writeJson(out: OutputStream, status: Int, json: String, headOnly: Boolean) {
        writeBody(out, status, "application/json", json.toByteArray(Charsets.UTF_8), headOnly)
    }
}

/** Owns the single relay instance used for casting. */
object RelayManager {

    private var relay: HlsRelay? = null

    @Volatile
    var activeChannelId: String? = null
        private set

    @Volatile
    var activeUrl: String? = null
        private set

    val isRunning: Boolean get() = relay?.isRunning == true

    /** Start (or reuse) the relay for [channel] and return the HLS url the TV should use. */
    fun ensure(channel: Channel, userAgent: String): String {
        val existing = relay
        if (existing != null && existing.isRunning && activeChannelId == channel.id && activeUrl != null) {
            return activeUrl!!
        }
        stop()
        val r = HlsRelay()
        try {
            r.start(channel.url, userAgent = userAgent)
        } catch (t: Throwable) {
            Logx.e("RELAY_FAILED_TO_START", t)
            return channel.url
        }
        relay = r
        activeChannelId = channel.id
        val ip = Net.lanIp()
        val url = r.playlistUrl(ip ?: "127.0.0.1")
        activeUrl = url
        if (ip == null) {
            Logx.w("RELAY_NO_LAN_IP - the TV will not be able to reach the relay")
        }
        return url
    }

    fun stop() {
        relay?.stop()
        relay = null
        activeChannelId = null
        activeUrl = null
    }

    /** Port the relay is listening on, 0 when it is not running. */
    val port: Int get() = relay?.port ?: 0

    /** Loopback playlist URL - used for on-device verification of the relay output. */
    fun localUrl(): String? {
        val p = port
        return if (p > 0) "http://127.0.0.1:$p/live.m3u8" else null
    }

    fun status(): String = relay?.statusJson() ?: "{\"running\":false}"
}

/** Small network helpers. */
object Net {

    /** First site-local IPv4 address, i.e. the address a TV on the same Wi-Fi can reach. */
    fun lanIp(): String? {
        return try {
            val candidates = ArrayList<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase(Locale.US)
                if (name.startsWith("tun") || name.startsWith("ppp")) continue
                for (addr in nif.inetAddresses) {
                    val host = addr.hostAddress ?: continue
                    if (host.contains(':')) continue
                    if (addr.isLoopbackAddress) continue
                    if (addr.isSiteLocalAddress) {
                        if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                            return host
                        }
                        candidates.add(host)
                    }
                }
            }
            candidates.firstOrNull()
        } catch (t: Throwable) {
            Logx.w("lanIp failed: ${t.message}")
            null
        }
    }
}
