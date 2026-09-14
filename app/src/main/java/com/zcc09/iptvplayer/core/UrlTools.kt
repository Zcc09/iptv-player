package com.zcc09.iptvplayer.core

/** Pure-Kotlin url helpers (unit tested). */
object UrlTools {

    /** Swap the file extension of the last path segment, keeping any query string. */
    fun withExtension(url: String, ext: String): String {
        val q = url.indexOf('?')
        val base = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        val slash = base.lastIndexOf('/')
        val dot = base.lastIndexOf('.')
        return if (dot > slash && dot >= 0) {
            base.substring(0, dot) + "." + ext + query
        } else {
            base + "." + ext + query
        }
    }

    fun isHls(url: String): Boolean {
        val u = url.substringBefore('?').lowercase()
        return u.endsWith(".m3u8") || u.contains(".m3u8")
    }

    fun isDash(url: String): Boolean = url.substringBefore('?').lowercase().endsWith(".mpd")

    /** True when the URL clearly carries MPEG-TS (or an extension-less IPTV proxy path). */
    fun looksLikeTs(url: String): Boolean {
        val u = url.substringBefore('?').lowercase()
        if (u.endsWith(".ts") || u.endsWith(".mts") || u.endsWith(".m2ts")) return true
        if (u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".m3u8") || u.endsWith(".mpd")) return false
        // Extension-less paths such as /proxy/ts/stream/<uuid> - almost always raw TS.
        return true
    }

    fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host ?: ""
    }.getOrDefault("")

    fun fileName(url: String): String =
        url.substringBefore('?').substringAfterLast('/').ifBlank { "stream" }
}

/**
 * Xtream Codes URL normalisation, kept dependency-free so it is unit tested.
 */
object XtreamUrls {

    private val SUFFIXES = listOf(
        "/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php",
        "/output/m3u", "/output/m3u_plus", "/output/m3u8", "/output/epg",
        "/output/xtream", "/output/hls"
    )

    /** Reduce anything the user pastes to `scheme://host[:port][/path]`. */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        s = s.trimEnd('/')
        var changed = true
        while (changed) {
            changed = false
            for (suffix in SUFFIXES) {
                if (s.endsWith(suffix)) {
                    s = s.dropLast(suffix.length).trimEnd('/')
                    changed = true
                }
            }
        }
        return s
    }
}
