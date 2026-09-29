package com.zcc09.iptvplayer.core

import kotlinx.serialization.Serializable

enum class PlaylistType { M3U, XTREAM }

/** Interface mode: Auto-detect (TV/widescreen/gamepad), Force Android TV, or Force Mobile. */
enum class AppUiMode {
    AUTO,
    TV,
    MOBILE
}

/** Cast behaviour for a playlist. */
enum class CastMode {
    /** HLS sources are cast directly, everything else goes through the on-device HLS relay. */
    AUTO,

    /** Always hand the raw source URL to the TV. */
    DIRECT,

    /** Always re-publish the stream as HLS from this phone. */
    RELAY
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val type: PlaylistType,
    /** M3U url, or the Xtream Codes base url (e.g. http://host:port). */
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val autoRefresh: Boolean = true,
    /** Minutes between background refreshes (15 minimum). */
    val refreshIntervalMinutes: Int = 360,
    val lastRefresh: Long = 0L,
    val lastRefreshOk: Boolean = false,
    val lastError: String = "",
    val channelCount: Int = 0
)

@Serializable
data class Channel(
    /** Stable across refreshes as long as the source keeps the same order/ids. */
    val id: String,
    val playlistId: String,
    val name: String,
    val group: String = "",
    val logo: String = "",
    val url: String = "",
    val epgId: String = "",
    val number: Int = 0,
    /** Xtream stream id, 0 for plain M3U entries. */
    val streamId: Int = 0
) {
    val isLive: Boolean get() = true
}

/** Result of parsing an M3U document. */
data class ParsedM3u(
    val channels: List<Channel>,
    val epgUrl: String = "",
    val userAgent: String? = null
)

/** Which catalogue an entry came from. */
enum class MediaKind { LIVE, MOVIE, SERIES }

/**
 * A VOD category. Panels expose hundreds of these (the test server has 443 film
 * and 381 series categories), so the UI lists them lazily rather than all at once.
 */
@Serializable
data class VodCategory(
    val id: String,
    val name: String,
    val kind: MediaKind
)

/**
 * One film or series from a VOD category.
 *
 * Catalogue sizes make "load everything" impossible - the test server serves
 * 72k films in a 38 MB response - so items are always fetched per category.
 * A series carries [containerExtension] = "" because its extension belongs to
 * each episode, which is fetched separately via the series info call.
 */
@Serializable
data class VodItem(
    val id: Int,
    val playlistId: String,
    val name: String,
    val categoryId: String = "",
    val icon: String = "",
    val containerExtension: String = "",
    val kind: MediaKind = MediaKind.MOVIE,
    /** Panel-supplied playable URL; empty on most panels, but preferred when set. */
    val directSource: String = "",
    val year: String = "",
    val rating: String = ""
)

/** One episode inside a series. */
@Serializable
data class Episode(
    val id: Int,
    val season: Int,
    val number: Int,
    val title: String,
    val containerExtension: String = "mkv",
    val directSource: String = ""
)


/** Result of a playlist refresh. */
data class RefreshResult(
    val channels: List<Channel>,
    val epgUrl: String = "",
    val userAgent: String? = null,
    val error: String? = null
) {
    val ok: Boolean get() = error == null
}
