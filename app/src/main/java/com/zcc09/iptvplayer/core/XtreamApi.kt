package com.zcc09.iptvplayer.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Xtream Codes (XC) API client.
 *
 * Normalises whatever the user pastes: bare host, `host:port`, a full
 * `get.php` / `player_api.php` URL, or a Dispatcharr-style `/output/m3u`
 * playlist URL all resolve to the same panel base.
 */
class XtreamApi(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val userAgent: String = Http.DEFAULT_UA
) {
    val base: String = XtreamUrls.normalize(baseUrl)

    data class Auth(
        val ok: Boolean,
        val status: String,
        val formats: List<String>,
        val maxConnections: String,
        val expire: String,
        val raw: String
    )

    data class Category(val id: String, val name: String)

    data class Stream(
        val id: Int,
        val name: String,
        val icon: String,
        val categoryId: String,
        val epgId: String,
        val number: Int,
        val directSource: String,
        val tvArchive: Boolean
    )

    private fun apiUrl(action: String? = null): String {
        val sb = StringBuilder(base)
        sb.append("/player_api.php?username=").append(enc(username))
        sb.append("&password=").append(enc(password))
        if (action != null) sb.append("&action=").append(enc(action))
        return sb.toString()
    }

    fun auth(): Auth {
        val body = Http.getText(apiUrl(), ua = userAgent)
        val root = JSONObject(body)
        val info = root.optJSONObject("user_info") ?: JSONObject()
        val authFlag = when (val v = info.opt("auth")) {
            is Boolean -> if (v) 1 else 0
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
        val formats = ArrayList<String>()
        info.optJSONArray("allowed_output_formats")?.let { arr ->
            for (i in 0 until arr.length()) formats.add(arr.optString(i))
        }
        return Auth(
            ok = authFlag == 1,
            status = info.optString("status", "Unknown"),
            formats = formats,
            maxConnections = info.optString("max_connections", ""),
            expire = info.optString("exp_date", ""),
            raw = body
        )
    }

    fun liveCategories(): List<Category> {
        val body = Http.getText(apiUrl("get_live_categories"), ua = userAgent)
        val out = ArrayList<Category>()
        val arr = JSONArrayIfArray(body)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Category(
                    id = o.optString("category_id"),
                    name = o.optString("category_name", "Uncategorised")
                )
            )
        }
        return out
    }

    fun liveStreams(): List<Stream> {
        val body = Http.getText(apiUrl("get_live_streams"), ua = userAgent)
        val arr = JSONArrayIfArray(body)
        val out = ArrayList<Stream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("stream_id", -1)
            if (id < 0) continue
            val catIds = o.optJSONArray("category_ids")
            val catId = when {
                catIds != null && catIds.length() > 0 -> catIds.optString(0)
                else -> o.optString("category_id", "")
            }
            out.add(
                Stream(
                    id = id,
                    name = o.optString("name", "Channel $id"),
                    icon = o.optString("stream_icon", ""),
                    categoryId = catId,
                    epgId = o.optString("epg_channel_id", ""),
                    number = o.optInt("num", id),
                    directSource = o.optString("direct_source", ""),
                    tvArchive = o.optInt("tv_archive", 0) == 1
                )
            )
        }
        return out
    }

    /** Best live-stream extension for this account, given the advertised formats. */
    fun preferredExtension(formats: List<String>): String = when {
        formats.contains("ts") -> "ts"
        formats.contains("mp4") -> "mp4"
        formats.contains("m3u8") -> "m3u8"
        formats.isEmpty() -> "ts"
        else -> formats.first()
    }

    fun streamUrl(stream: Stream, ext: String): String {
        if (stream.directSource.isNotBlank() &&
            (stream.directSource.startsWith("http://") || stream.directSource.startsWith("https://"))
        ) {
            return stream.directSource
        }
        return "$base/live/${enc(username)}/${enc(password)}/${stream.id}.$ext"
    }

    private fun JSONArrayIfArray(body: String): JSONArray {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val o = JSONObject(trimmed)
            val key = listOf("available_channels", "channels", "data", "results")
                .firstOrNull { o.has(it) }
            if (key != null) return o.optJSONArray(key) ?: JSONArray()
            return JSONArray()
        }
        return JSONArray(trimmed)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
