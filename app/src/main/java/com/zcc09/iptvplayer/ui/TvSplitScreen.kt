package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zcc09.iptvplayer.core.Channel
import com.zcc09.iptvplayer.core.EpgStore
import com.zcc09.iptvplayer.core.Repo
import com.zcc09.iptvplayer.player.Playback
import kotlinx.coroutines.delay

/**
 * Wide / TV layout: the list is for browsing, the player is for watching.
 *
 * Left: every channel, D-pad focusable. Right: a muted preview of the focused
 * channel, the guide for it underneath, and the stream facts (resolution, frame
 * rate, bitrate, container) below that. Choosing with OK opens the full player.
 *
 * The preview player is its own ExoPlayer instance so browsing never disturbs a
 * full-screen session, and it stays muted so a preview is not an ambush.
 */
@Composable
fun TvSplitScreen(playlistId: String) {
    val channels = remember(playlistId) { Repo.channelsOf(playlistId) }
    val playlist = remember(playlistId) { Repo.playlist(playlistId) }
    var selected by remember(playlistId) { mutableStateOf(channels.firstOrNull()) }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val preview = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { preview.release() }
    }

    // Point the preview at whatever is focused, without restarting on repaint.
    LaunchedEffect(selected?.id) {
        val channel = selected ?: return@LaunchedEffect
        if (channel.url.isBlank()) return@LaunchedEffect
        preview.setMediaItem(Playback.mediaItem(channel.url, channel.name, channel.logo))
        preview.prepare()
        preview.play()
    }

    // Guide tick, so "now" advances while the screen is open.
    LaunchedEffect(playlistId) {
        while (true) {
            delay(60_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // ------------------------------------------------------------- channel list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight()
                .focusRequester(focusRequester)
                .focusable(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                Text(
                    text = playlist?.name ?: "Channels",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp)
                )
            }
            items(channels, key = { it.id }) { channel ->
                SplitChannelRow(
                    channel = channel,
                    selected = channel.id == selected?.id,
                    onFocus = { selected = channel },
                    onOpen = { Nav.push(Screen.Player(channel)) }
                )
            }
        }

        // ---------------------------------------------------------------- preview
        Column(
            modifier = Modifier
                .weight(0.62f)
                .fillMaxHeight()
                .padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            keepScreenOn = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            player = preview
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (selected == null) {
                    Text(
                        "Choose a channel to preview it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            NowNextBlock(playlistId = playlistId, channel = selected, nowSec = nowSec)
            Spacer(Modifier.height(10.dp))
            StreamFacts(preview = preview, channel = selected, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SplitChannelRow(
    channel: Channel,
    selected: Boolean,
    onFocus: () -> Unit,
    onOpen: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.surfaceVariant
                    focused -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else -> Color.Transparent
                }
            )
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .focusable()
            // Focus previews the channel; activating it opens the full player.
            // Without this the row was focusable but inert on a remote's OK.
            .clickable { onOpen() }
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        if (channel.number > 0) {
            Text(
                text = channel.number.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(38.dp)
            )
        }
        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

/** The guide for the focused channel: what is on now, what is next. */
@Composable
private fun NowNextBlock(playlistId: String, channel: Channel?, nowSec: Long) {
    val now = remember(channel?.id, nowSec) {
        channel?.let { EpgStore.nowProgram(playlistId, it, nowSec) }
    }
    val next = remember(channel?.id, nowSec) {
        channel?.let { EpgStore.nextProgram(playlistId, it, nowSec) }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "Now",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = now?.title?.takeIf { it.isNotBlank() } ?: "No guide data for this channel",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Next",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = next?.title?.takeIf { it.isNotBlank() } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * What the stream actually is: resolution, frame rate, bitrate and container.
 * Polled because these arrive after the first frames decode.
 */
@Composable
private fun StreamFacts(preview: ExoPlayer, channel: Channel?, modifier: Modifier = Modifier) {
    var facts by remember { mutableStateOf("Connecting…") }
    LaunchedEffect(channel?.id) {
        while (true) {
            val size = preview.videoSize
            val format = preview.videoFormat
            val parts = buildList {
                if (size.width > 0 && size.height > 0) add("${size.width}×${size.height}")
                format?.frameRate?.takeIf { it > 0 }?.let { add("${it.toInt()} fps") }
                format?.bitrate?.takeIf { it > 0 }?.let { add("${it / 1000} kbps") }
                val container = when {
                    channel == null -> null
                    channel.url.contains(".m3u8", true) -> "HLS"
                    channel.url.contains(".mkv", true) -> "MKV"
                    channel.url.contains(".mp4", true) -> "MP4"
                    else -> "TS"
                }
                container?.let { add(it) }
            }
            facts = if (parts.isEmpty()) "Connecting…" else parts.joinToString("  ·  ")
            delay(1_000)
        }
    }
    Text(
        text = facts,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
