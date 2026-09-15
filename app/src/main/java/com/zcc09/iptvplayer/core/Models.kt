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

/** Result of a playlist refresh. */
data class RefreshResult(
    val channels: List<Channel>,
    val epgUrl: String = "",
    val userAgent: String? = null,
    val error: String? = null
) {
    val ok: Boolean get() = error == null
}
