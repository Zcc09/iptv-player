package com.zcc09.iptvplayer.core

/**
 * M3U / M3U-Plus playlist parser.
 *
 * Pure Kotlin (no Android dependencies) so it is covered by plain JVM unit tests.
 * Handles: #EXTINF attributes (quoted or bare), display names containing commas,
 * #EXTGRP, #EXTVLCOPT user-agent, CRLF, BOM and blank lines.
 */
object M3uParser {

    private val ATTR = Regex("([A-Za-z0-9_.-]+)\\s*=\\s*(\"([^\"]*)\"|([^\\s,\"]+))")

    private fun attrs(segment: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in ATTR.findAll(segment)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
            out[key] = value
        }
        return out
    }

    /**
     * Index of the first comma that is not inside a quoted attribute value.
     * That comma separates the attributes from the display name; display names
     * themselves may contain commas, so the *last* comma would be wrong.
     */
    private fun firstUnquotedComma(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') inQuotes = !inQuotes else if (c == ',' && !inQuotes) return i
        }
        return -1
    }

    fun parse(text: String, playlistId: String): ParsedM3u {
        val channels = ArrayList<Channel>()
        var epgUrl = ""
        var vlcUa: String? = null

        var pendingAttrs: Map<String, String>? = null
        var pendingName: String? = null
        var pendingGroup = ""
        var ordinal = 0

        val lines = text.split('\n')
        for (raw in lines) {
            var line = raw.trim()
            if (line.startsWith("\uFEFF")) line = line.substring(1).trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U") -> {
                    val a = attrs(line)
                    epgUrl = a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"] ?: ""
                    val ua = a["user-agent"]
                    if (!ua.isNullOrBlank()) vlcUa = ua
                    continue
                }

                line.startsWith("#EXTINF") -> {
                    val payload = line.substringAfter('#').substringAfter(':')
                    val comma = firstUnquotedComma(payload)
                    val attrPart = if (comma >= 0) payload.substring(0, comma) else payload
                    val namePart = if (comma >= 0) payload.substring(comma + 1) else ""
                    pendingAttrs = attrs(attrPart)
                    pendingName = namePart.trim().ifBlank { null }
                    continue
                }

                line.startsWith("#EXTGRP:") -> {
                    pendingGroup = line.substringAfter(':').trim()
                    continue
                }

                line.startsWith("#EXTVLCOPT:") || line.startsWith("#EXTHTTP:") -> {
                    val opt = line.substringAfter(':').trim()
                    val eq = opt.indexOf('=')
                    if (eq > 0) {
                        val k = opt.substring(0, eq).trim().lowercase()
                        val v = opt.substring(eq + 1).trim()
                        if ((k == "http-user-agent" || k == "user-agent") && v.isNotBlank()) vlcUa = v
                    }
                    continue
                }

                line.startsWith("#KODIPROP:") || line.startsWith("#EXT-X-") -> continue

                line.startsWith("#") -> continue
            }

            // Anything left that looks like a stream location becomes a channel.
            if (isLocation(line)) {
                val a = pendingAttrs ?: emptyMap()
                val name = pendingName
                    ?: a["tvg-name"]?.takeIf { it.isNotBlank() }
                    ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "Channel ${ordinal + 1}" }
                val group = a["group-title"]?.takeIf { it.isNotBlank() }
                    ?: pendingGroup
                channels.add(
                    Channel(
                        id = "$playlistId#$ordinal",
                        playlistId = playlistId,
                        name = name,
                        group = group,
                        logo = a["tvg-logo"] ?: a["logo"] ?: "",
                        url = line,
                        epgId = a["tvg-id"] ?: "",
                        number = a["tvg-chno"]?.toIntOrNull() ?: 0
                    )
                )
                ordinal++
                pendingAttrs = null
                pendingName = null
            }
        }
        return ParsedM3u(channels, epgUrl, vlcUa)
    }

    private fun isLocation(line: String): Boolean {
        val l = line.lowercase()
        return l.startsWith("http://") || l.startsWith("https://") ||
            l.startsWith("rtmp://") || l.startsWith("rtsp://") ||
            l.startsWith("udp://") || l.startsWith("rtp://") ||
            l.startsWith("mms://") || l.startsWith("file:")
    }
}
