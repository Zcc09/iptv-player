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

    /**
     * VOD URLs on an Xtream panel. `movie/<user>/<pass>/<stream_id>.<ext>` for a
     * film, `series/<user>/<pass>/<episode_id>.<ext>` for one episode. Panels
     * commonly 301-redirect these to the same path with a `?session_id=…` added,
     * which the player follows.
     */
    fun movie(base: String, user: String, pass: String, streamId: Int, ext: String): String =
        "$base/movie/${encode(user)}/${encode(pass)}/$streamId.${cleanExt(ext, "mp4")}"

    fun episode(base: String, user: String, pass: String, episodeId: Int, ext: String): String =
        "$base/series/${encode(user)}/${encode(pass)}/$episodeId.${cleanExt(ext, "mkv")}"

    /** Panels are inconsistent: some send "mkv", some ".mkv". Never emit "5..mkv". */
    private fun cleanExt(ext: String, fallback: String): String =
        ext.trim().trimStart('.').ifBlank { fallback }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

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
