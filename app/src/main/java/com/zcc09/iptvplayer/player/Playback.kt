package com.zcc09.iptvplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.zcc09.iptvplayer.core.Channel
import com.zcc09.iptvplayer.core.UrlTools

/**
 * Media3 plumbing: player construction, container hints and the URL a TV should
 * be handed for a given channel.
 */
object Playback {

    fun createPlayer(context: Context, userAgent: String): ExoPlayer {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSource)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    /**
     * Container hint. IPTV endpoints are usually raw MPEG-TS, and many of them
     * (Xtream proxies, Dispatcharr's /proxy/ts/... ) carry no file extension at
     * all, so TS is the default rather than the fallback.
     */
    fun mimeFor(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            UrlTools.isHls(url) -> MimeTypes.APPLICATION_M3U8
            UrlTools.isDash(url) -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> MimeTypes.VIDEO_MP2T
        }
    }

    fun mediaItem(url: String, title: String, logo: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .apply { if (logo.isNotBlank()) setArtworkUri(Uri.parse(logo)) }
            .build()
        return MediaItem.Builder()
            .setUri(url)
            .setMimeType(mimeFor(url))
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * DIRECT cast mode: hand the TV a URL it has a chance of playing.
     * Chromecast's default receiver cannot play raw MPEG-TS, but it can play
     * HLS/DASH and usually fragmented MP4 - so an Xtream `.ts` url is swapped
     * for the `.mp4` variant, which most panels (Dispatcharr included) serve
     * as fragmented MP4.
     */
    fun directCastUrl(channel: Channel): String {
        val url = channel.url
        if (UrlTools.isHls(url) || UrlTools.isDash(url)) return url
        if (channel.streamId > 0 && url.substringBefore('?').lowercase().endsWith(".ts")) {
            return UrlTools.withExtension(url, "mp4")
        }
        return url
    }
}
