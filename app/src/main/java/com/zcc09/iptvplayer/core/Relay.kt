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
/**
 * Splits a continuous MPEG-TS byte stream into HLS segments.
 *
 * Segments are cut at **H.264/HEVC keyframe access units** (and the latest
 * PAT+PMT is repeated at the start of every segment) so each segment is
 * independently decodable - a naive byte/PAT-boundary split produces segments
 * that start mid-GOP and players report "non-existing PPS 0 referenced".
 *
 * If no PMT/video PID can be found (unusual muxes), it falls back to cutting at
 * PAT boundaries, which still yields a playable - if occasionally glitchy -
 * stream. Nothing is re-encoded: the TS payload is relayed byte-for-byte, so
 * audio/video stay in sync and CPU use is negligible.
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
        const val TARGET_DURATION = 8

        private const val MIN_TARGET_BYTES = 400_000
        private const val MAX_TARGET_BYTES = 12_000_000
        private const val RATE_WINDOW_MS = 8_000L
        private const val RATE_MIN_SAMPLE_MS = 3_000L
        private const val RATE_MIN_SAMPLE_BYTES = 512L * 1024
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_HEVC = 0x24
        private const val MODE_DECISION_BYTES = 4L * 1024 * 1024

        // NAL unit types that mark a self-contained random access point.
        private const val NAL_H264_IDR = 5
        private const val NAL_H264_SPS = 7
        private const val NAL_H264_SLICE = 1
        private const val NAL_HEVC_VPS = 32
        private const val NAL_HEVC_SPS = 33
        private const val NAL_HEVC_IRAP_START = 16
        private const val NAL_HEVC_IRAP_END = 23
    }

    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val segments = ArrayDeque<Segment>()
    private val buf = ByteArrayOutputStream(4 * 1024 * 1024)

    /** Assembly buffer for the packet currently being read. */
    private val packet = ByteArray(TS_PACKET)
    private var filled = 0

    private var nextSeq = 1L
    private var targetBytes = initialTargetBytes
    private var fedBytes = 0L
    private var produced = 0

    /** [timestamp, cumulative bytes] samples for the sliding-window bitrate. */
    private val rateSamples = ArrayDeque<LongArray>()

    // Programme specific information / codec tracking.
    private var pmtPid = -1
    private var videoPid = -1
    private var videoCodec = 0
    private var patPacket: ByteArray? = null
    private var pmtPacket: ByteArray? = null
    private var analysedBytes = 0L
    private var bufferStartsWithPat = false

    val producedSegments: Int get() = lock.withLock { produced }

    /** "keyframe" once the video stream was identified, "pat-fallback" otherwise. */
    val modeName: String
        get() = when {
            videoPid >= 0 -> "keyframe"
            analysedBytes >= MODE_DECISION_BYTES -> "pat-fallback"
            else -> "detecting"
        }

    val codecName: String
        get() = when (videoCodec) {
            STREAM_TYPE_H264 -> "h264"
            STREAM_TYPE_HEVC -> "hevc"
            else -> "unknown"
        }

    fun feed(chunk: ByteArray, len: Int) {
        if (len <= 0) return
        fedBytes += len
        var off = 0
        while (off < len) {
            // The upstream is read in arbitrary chunk sizes, so packets are
            // assembled here rather than assumed to be aligned to the chunk.
            if (filled == 0 && chunk[off] != 0x47.toByte()) {
                off++
                continue
            }
            val take = minOf(TS_PACKET - filled, len - off)
            System.arraycopy(chunk, off, packet, filled, take)
            filled += take
            off += take
            if (filled == TS_PACKET) {
                consumePacket()
                filled = 0
            }
        }
    }

    /** Process one complete 188-byte packet: track PSI and cut on keyframes. */
    private fun consumePacket() {
        val pid = pidOf(packet, 0)
        val payloadStart = (packet[1].toInt() and 0x40) != 0
        if (payloadStart) {
            when {
                pid == 0 -> capturePat(packet, 0)
                pmtPid >= 0 && pid == pmtPid -> capturePmt(packet, 0)
            }
        }

        val keyframe = videoPid >= 0 && pid == videoPid && payloadStart &&
            accessUnitIsKeyframe(packet, 0)
        val patBoundary = videoPid < 0 && pid == 0 && payloadStart

        if (buf.size() >= targetBytes && (keyframe || patBoundary)) {
            flush()
        } else if (videoPid >= 0 && pid == videoPid && payloadStart &&
            buf.size() >= targetBytes * 2
        ) {
            // Long GOP guard: never let a single segment grow unbounded.
            flush()
        }

        if (buf.size() == 0 && pid == 0 && payloadStart) {
            bufferStartsWithPat = true
        }
        buf.write(packet, 0, TS_PACKET)
        if (videoPid < 0) analysedBytes += TS_PACKET
    }

    /** Flush whatever is buffered (called when the upstream stream ends). */
    fun finish() {
        if (buf.size() >= TS_PACKET) flush(force = true)
    }

    // ------------------------------------------------------------------ parsing

    private fun pidOf(data: ByteArray, off: Int): Int =
        ((data[off + 1].toInt() and 0x1F) shl 8) or (data[off + 2].toInt() and 0xFF)

    /** Byte offset of the payload inside a TS packet (after any adaptation field). */
    private fun payloadOffset(data: ByteArray, off: Int): Int {
        return if ((data[off + 3].toInt() and 0x20) != 0) {
            off + 5 + (data[off + 4].toInt() and 0xFF)
        } else {
            off + 4
        }
    }

    /** Offset of the section body (skipping the pointer_field), or -1. */
    private fun sectionOffset(data: ByteArray, off: Int): Int {
        val payload = payloadOffset(data, off)
        if (payload + 1 >= off + TS_PACKET) return -1
        val pointer = data[payload].toInt() and 0xFF
        val section = payload + 1 + pointer
        return if (section + 8 >= off + TS_PACKET) -1 else section
    }

    private fun capturePat(data: ByteArray, off: Int) {
        val section = sectionOffset(data, off)
        if (section < 0) return
        if (data[section].toInt() and 0xFF != 0x00) return // table_id 0 = PAT
        val sectionLength = ((data[section + 1].toInt() and 0x0F) shl 8) or
            (data[section + 2].toInt() and 0xFF)
        val end = minOf(section + 3 + sectionLength - 4, off + TS_PACKET)
        var s = section + 8
        while (s + 3 < end) {
            val program = ((data[s].toInt() and 0xFF) shl 8) or (data[s + 1].toInt() and 0xFF)
            val pid = ((data[s + 2].toInt() and 0x1F) shl 8) or (data[s + 3].toInt() and 0xFF)
            if (program != 0) {
                if (pid != pmtPid) {
                    pmtPid = pid
                    videoPid = -1
                }
                patPacket = data.copyOfRange(off, off + TS_PACKET)
                return
            }
            s += 4
        }
    }

    private fun capturePmt(data: ByteArray, off: Int) {
        val section = sectionOffset(data, off)
        if (section < 0) return
        if (data[section].toInt() and 0xFF != 0x02) return // table_id 2 = PMT
        val sectionLength = ((data[section + 1].toInt() and 0x0F) shl 8) or
            (data[section + 2].toInt() and 0xFF)
        var s = section + 12
        val programInfoLength = ((data[section + 10].toInt() and 0x0F) shl 8) or
            (data[section + 11].toInt() and 0xFF)
        s += programInfoLength
        val end = minOf(section + 3 + sectionLength - 4, off + TS_PACKET)
        var h264Pid = -1
        var otherPid = -1
        var otherCodec = 0
        while (s + 4 < end) {
            val streamType = data[s].toInt() and 0xFF
            val pid = ((data[s + 1].toInt() and 0x1F) shl 8) or (data[s + 2].toInt() and 0xFF)
            val esInfoLength = ((data[s + 3].toInt() and 0x0F) shl 8) or (data[s + 4].toInt() and 0xFF)
            if (streamType == STREAM_TYPE_H264) {
                h264Pid = pid
            } else if (otherPid < 0 && streamType == STREAM_TYPE_HEVC) {
                otherPid = pid
                otherCodec = streamType
            }
            s += 5 + esInfoLength
        }
        pmtPacket = data.copyOfRange(off, off + TS_PACKET)
        when {
            h264Pid >= 0 -> {
                videoPid = h264Pid
                videoCodec = STREAM_TYPE_H264
            }

            otherPid >= 0 -> {
                videoPid = otherPid
                videoCodec = otherCodec
            }
        }
    }

    /**
     * True when this packet starts an access unit that carries SPS/IDR (H.264)
     * or VPS/SPS/IRAP (HEVC), i.e. a point where a player can start decoding.
     */
    private fun accessUnitIsKeyframe(data: ByteArray, off: Int): Boolean {
        val payload = payloadOffset(data, off)
        val packetEnd = off + TS_PACKET
        if (payload + 9 >= packetEnd) return false
        if (data[payload] != 0.toByte() ||
            data[payload + 1] != 0.toByte() ||
            data[payload + 2] != 1.toByte()
        ) {
            return false
        }
        // PES header: startcode(3) stream_id(1) length(2) flags(2) header_data_length(1)
        var s = payload + 9 + (data[payload + 8].toInt() and 0xFF)
        var scanned = 0
        while (s + 3 < packetEnd && scanned < 24) {
            if (data[s] == 0.toByte() && data[s + 1] == 0.toByte() && data[s + 2] == 1.toByte()) {
                if (videoCodec == STREAM_TYPE_HEVC) {
                    // HEVC NAL header: forbidden(1) type(6) layer_id(6) tid+1(3)
                    val type = (data[s + 3].toInt() and 0x7E) shr 1
                    if (type in NAL_HEVC_IRAP_START..NAL_HEVC_IRAP_END) return true
                    if (type == NAL_HEVC_VPS || type == NAL_HEVC_SPS) return true
                    if (type <= 15) return false // VCL slice, not a random access point
                } else {
                    val type = data[s + 3].toInt() and 0x1F
                    if (type == NAL_H264_IDR || type == NAL_H264_SPS) return true
                    if (type == NAL_H264_SLICE) return false
                }
                scanned++
                s += 4
            } else {
                s++
            }
        }
        return false
    }

    /** Latest PAT+PMT, repeated at the head of every keyframe-cut segment. */
    private fun psiPrefix(): ByteArray? {
        if (videoPid < 0) return null
        val pat = patPacket ?: return null
        val pmt = pmtPacket ?: return null
        val out = ByteArray(2 * TS_PACKET)
        System.arraycopy(pat, 0, out, 0, TS_PACKET)
        System.arraycopy(pmt, 0, out, TS_PACKET, TS_PACKET)
        return out
    }

    // ------------------------------------------------------------------ segments

    private fun flush(force: Boolean = false) {
        val size = buf.size()
        if (size < TS_PACKET) return
        if (!force && size < minSegmentBytes) return

        val now = System.currentTimeMillis()
        var data = buf.toByteArray()
        buf.reset()

        // Repeat the programme tables at the head of the segment, unless the
        // segment already begins with a PAT (then the PMT follows right after).
        val prefix = if (bufferStartsWithPat) null else psiPrefix()
        bufferStartsWithPat = false
        if (prefix != null) data = prefix + data

        // Measure the bitrate over a sliding window of recent flushes. A
        // cumulative average (or a per-flush delta) is skewed by the initial
        // burst every IPTV proxy sends, which would inflate the segment-size
        // target and produce 15s+ segments.
        rateSamples.addLast(longArrayOf(now, fedBytes))
        while (rateSamples.size > 2 && now - rateSamples.first()[0] > RATE_WINDOW_MS) {
            rateSamples.removeFirst()
        }
        val oldest = rateSamples.first()
        val spanMs = now - oldest[0]
        val windowBytes = fedBytes - oldest[1]
        val enoughSample = spanMs >= RATE_MIN_SAMPLE_MS && windowBytes >= RATE_MIN_SAMPLE_BYTES
        val streamBps = if (enoughSample) (windowBytes * 8.0) / (spanMs / 1000.0) else 0.0

        if (streamBps > 100_000) {
            targetBytes = (streamBps * targetSeconds / 8.0).toInt()
                .coerceIn(MIN_TARGET_BYTES, MAX_TARGET_BYTES)
        }

        val duration = if (streamBps > 50_000) {
            (size * 8.0) / streamBps
        } else {
            targetSeconds
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
        append(",\"mode\":\"").append(segmenter?.modeName ?: "none").append('"')
        append(",\"codec\":\"").append(segmenter?.codecName ?: "-").append('"')
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
                var lastMode = ""
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
                        if (seg.modeName != lastMode) {
                            lastMode = seg.modeName
                            Logx.i("RELAY_MODE ${seg.modeName} codec=${seg.codecName}")
                        }
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
