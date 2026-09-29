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

    private fun apiUrl(action: String? = null, extra: String? = null): String {
        val sb = StringBuilder(base)
        sb.append("/player_api.php?username=").append(enc(username))
        sb.append("&password=").append(enc(password))
        if (action != null) sb.append("&action=").append(enc(action))
        if (extra != null) sb.append("&").append(extra)
        return sb.toString()
    }

    // ------------------------------------------------------------------ VOD
    // Catalogues get big: a real panel serves tens of thousands of films in one
    // 38 MB response, so the app never lists "all films" - it always asks for one
    // category at a time (`&category_id=`), which the panels honour.

    data class VodStream(
        val id: Int,
        val name: String,
        val icon: String,
        val categoryId: String,
        val containerExtension: String
    )

    data class SeriesEntry(
        val id: Int,
        val name: String,
        val cover: String,
        val categoryId: String
    )

    fun vodCategories(): List<Category> = categories("get_vod_categories")

    fun seriesCategories(): List<Category> = categories("get_series_categories")

    private fun categories(action: String): List<Category> {
        val body = Http.getText(apiUrl(action), ua = userAgent)
        val arr = JSONArrayIfArray(body)
        val out = ArrayList<Category>(arr.length())
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

    fun vodStreams(categoryId: String? = null): List<VodStream> {
        val body = Http.getText(
            apiUrl("get_vod_streams", categoryId?.takeIf { it.isNotBlank() }?.let { "category_id=" + enc(it) }),
            ua = userAgent
        )
        val arr = JSONArrayIfArray(body)
        val out = ArrayList<VodStream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("stream_id", -1)
            if (id < 0) continue
            out.add(
                VodStream(
                    id = id,
                    name = o.optString("name", "Movie $id"),
                    icon = o.optString("stream_icon", ""),
                    categoryId = firstCategoryId(o),
                    containerExtension = o.optString("container_extension", "mp4").ifBlank { "mp4" }
                )
            )
        }
        return out
    }

    fun series(categoryId: String? = null): List<SeriesEntry> {
        val body = Http.getText(
            apiUrl("get_series", categoryId?.takeIf { it.isNotBlank() }?.let { "category_id=" + enc(it) }),
            ua = userAgent
        )
        val arr = JSONArrayIfArray(body)
        val out = ArrayList<SeriesEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("series_id", -1)
            if (id < 0) continue
            out.add(
                SeriesEntry(
                    id = id,
                    name = o.optString("name", "Series $id"),
                    cover = o.optString("cover", ""),
                    categoryId = firstCategoryId(o)
                )
            )
        }
        return out
    }

    /**
     * Episodes of one series, flattened across seasons.
     *
     * The payload is `{ seasons: [...], info: {...}, episodes: { "1": [ {...} ] } }`
     * and episode ids are what the playback URL needs.
     */
    fun seriesInfo(seriesId: Int): List<Episode> {
        val body = Http.getText(
            apiUrl("get_series_info", "series_id=$seriesId"),
            ua = userAgent
        )
        return parseSeriesInfo(body)
    }

    /** Kept separate from the network call so the shape can be unit tested. */
    internal fun parseSeriesInfo(body: String): List<Episode> {
        val root = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return emptyList()
        val eps = root.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Episode>()
        for (seasonKey in eps.keys()) {
            val season = seasonKey.toIntOrNull() ?: 0
            val arr = eps.optJSONArray(seasonKey) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").toIntOrNull() ?: o.optInt("id", -1)
                if (id < 0) continue
                out.add(
                    Episode(
                        id = id,
                        season = o.optString("season").toIntOrNull() ?: season,
                        number = o.optString("episode_num").toIntOrNull() ?: (i + 1),
                        title = o.optString("title", "Episode ${i + 1}"),
                        containerExtension = o.optString("container_extension", "mkv").ifBlank { "mkv" }
                    )
                )
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    private fun firstCategoryId(o: JSONObject): String {
        val catIds = o.optJSONArray("category_ids")
        return when {
            catIds != null && catIds.length() > 0 -> catIds.optString(0)
            else -> o.optString("category_id", "")
        }
    }

    fun movieUrl(item: VodItem): String =
        XtreamUrls.movie(base, username, password, item.id, item.containerExtension)

    fun episodeUrl(episode: Episode): String =
        XtreamUrls.episode(base, username, password, episode.id, episode.containerExtension)

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
