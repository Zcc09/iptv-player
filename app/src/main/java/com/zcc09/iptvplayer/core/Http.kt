package com.zcc09.iptvplayer.core

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny HTTP helper built on HttpURLConnection so the app has no extra
 * networking dependency. Handles manual cross-protocol redirects, which the
 * default implementation refuses to follow.
 */
object Http {
    const val DEFAULT_UA = "IPTVPlayer/1.0 (Android)"

    fun open(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
        readTimeoutMs: Int = timeoutMs,
        ua: String = DEFAULT_UA
    ): HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Connection", "close")
            for ((k, v) in headers) {
                if (k.isNotBlank() && v.isNotBlank()) conn.setRequestProperty(k, v)
            }
            conn.connect()
            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IOException("HTTP $code without Location for $current")
                hops++
                if (hops > 5) throw IOException("Too many redirects for $url")
                current = if (loc.startsWith("http://") || loc.startsWith("https://")) {
                    loc
                } else {
                    URL(URL(current), loc).toString()
                }
                continue
            }
            return conn
        }
    }

    /** GET a small text document (playlist / Xtream JSON). */
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
        ua: String = DEFAULT_UA
    ): String {
        val conn = open(url, headers, timeoutMs, timeoutMs, ua)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = readFully(stream, gzipped = conn.contentEncoding?.contains("gzip") == true)
            if (code !in 200..299) {
                val snippet = body.take(180).replace('\n', ' ')
                throw IOException("HTTP $code from $url :: $snippet")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    fun openStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
        readTimeoutMs: Int = 30000,
        ua: String = DEFAULT_UA
    ): Pair<HttpURLConnection, InputStream> {
        val conn = open(url, headers, timeoutMs, readTimeoutMs, ua)
        val code = conn.responseCode
        if (code !in 200..299) {
            val snippet = try {
                readFully(conn.errorStream).take(180)
            } catch (t: Throwable) {
                ""
            }
            conn.disconnect()
            throw IOException("HTTP $code from $url :: $snippet")
        }
        return conn to conn.inputStream
    }

    /**
     * GET a text body as a stream, inflating gzip when the server says so or the
     * body starts with the gzip magic. Guides are tens of megabytes, so callers
     * stream this rather than materialising a String.
     */
    fun openTextStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30000,
        ua: String = DEFAULT_UA
    ): InputStream {
        val (conn, input) = openStream(url, headers, timeoutMs, timeoutMs, ua)
        val buffered = java.io.BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val magic = ByteArray(2)
        val n = buffered.read(magic)
        buffered.reset()
        val gz = conn.contentEncoding?.contains("gzip") == true ||
            (n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte())
        return if (gz) java.util.zip.GZIPInputStream(buffered, 64 * 1024) else buffered
    }

    /** GET a binary body with a safety cap (HLS segments, images). */
    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
        maxBytes: Int = 8 * 1024 * 1024,
        ua: String = DEFAULT_UA
    ): ByteArray {
        val (conn, input) = openStream(url, headers, timeoutMs, timeoutMs, ua)
        try {
            val out = java.io.ByteArrayOutputStream(256 * 1024)
            val buf = ByteArray(32 * 1024)
            while (out.size() < maxBytes) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            runCatching { input.close() }
            conn.disconnect()
        }
    }

    /**
     * Read a whole body as UTF-8. Public guides are often served as a `.xml.gz`
     * (or with `Content-Encoding: gzip`), so a gzip body is inflated rather than
     * parsed as binary noise.
     */
    private fun readFully(input: InputStream?, gzipped: Boolean = false): String {
        if (input == null) return ""
        return input.use { raw ->
            val peeked = java.io.PushbackInputStream(raw, 2)
            val magic = ByteArray(2)
            val n = peeked.read(magic)
            if (n > 0) peeked.unread(magic, 0, n)
            val isGzip = gzipped || (n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte())
            val stream: InputStream = if (isGzip) java.util.zip.GZIPInputStream(peeked) else peeked
            val buf = ByteArray(16 * 1024)
            val sb = StringBuilder()
            while (true) {
                val read = stream.read(buf)
                if (read < 0) break
                sb.append(String(buf, 0, read, Charsets.UTF_8))
            }
            sb.toString()
        }
    }
}
