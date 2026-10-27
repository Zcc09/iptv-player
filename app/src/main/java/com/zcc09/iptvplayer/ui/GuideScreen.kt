package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.core.Channel
import com.zcc09.iptvplayer.core.EpgParser
import com.zcc09.iptvplayer.core.EpgProgram
import com.zcc09.iptvplayer.core.EpgStore
import com.zcc09.iptvplayer.core.Logx
import com.zcc09.iptvplayer.core.PlaylistType
import com.zcc09.iptvplayer.core.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * EPG guide: an hour/30-minute time ruler with a NOW marker, and one row per
 * live channel holding programme blocks whose widths are proportional to their
 * duration over the configured window (2/4/6 h, default 4).
 *
 * [tv] renders the 10-foot variant (native D-pad/gamepad focus on every block,
 * Enter plays); both variants play the channel when a block is tapped.
 * Servers without a guide show a friendly no-guide state instead of failing.
 */
@Composable
fun GuideScreen(playlistId: String, tv: Boolean = false) {
    val scope = rememberCoroutineScope()
    val playlist = remember(playlistId) { Repo.playlist(playlistId) }
    val channelsMap by Repo.channels.collectAsState()
    val favorites by Repo.favorites.collectAsState()
    val cache by EpgStore.flow(playlistId).collectAsState()
    val all = channelsMap[playlistId].orEmpty()

    var group by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    val windowHours by Repo.epgWindowHours.collectAsState()

    val groups = remember(all) {
        all.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val shown = remember(all, group, favoritesOnly, favorites) {
        all.asSequence()
            .filter { group == null || it.group == group }
            .filter { !favoritesOnly || favorites.contains(it.id) }
            .toList()
    }

    // Refresh the guide when stale (the store no-ops within 10 minutes) and
    // tick a minute clock so the NOW marker and current programme advance.
    LaunchedEffect(playlistId) {
        playlist?.let { EpgStore.refresh(it) }
        while (true) {
            delay(60_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }

    // E2E: say what the guide is showing (the e2e build polls these lines).
    LaunchedEffect(cache?.fetchedAt, cache?.missing, shown.size) {
        val c = cache ?: return@LaunchedEffect
        if (c.missing) Logx.i("E2E EPG_MISSING")
        else Logx.i("E2E EPG programs=${c.programs.values.sumOf { it.size }} channels=${shown.size}")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            playlist?.type != PlaylistType.XTREAM -> CenteredNote(
                title = "No programme guide",
                body = "The guide is available for Xtream Codes accounts only."
            )

            cache == null -> CenteredLoading("Loading guide…")

            cache!!.missing -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No programme guide", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "This server does not provide a guide for these channels.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = { scope.launch { playlist?.let { EpgStore.refresh(it) } } }) {
                    Text("Retry")
                }
            }

            else -> GuideGrid(
                playlistId = playlistId,
                channels = shown,
                cache = cache!!,
                groups = groups,
                group = group,
                favoritesOnly = favoritesOnly,
                nowSec = nowSec,
                windowHours = windowHours,
                tv = tv,
                onGroup = { group = it },
                onFavoritesOnly = { favoritesOnly = it },
                onPlay = { channel -> Nav.push(Screen.Player(channel)) }
            )
        }
    }
}

@Composable
private fun GuideGrid(
    playlistId: String,
    channels: List<Channel>,
    cache: EpgStore.EpgCache,
    groups: List<String>,
    group: String?,
    favoritesOnly: Boolean,
    nowSec: Long,
    windowHours: Int,
    tv: Boolean,
    onGroup: (String?) -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    onPlay: (Channel) -> Unit
) {
    // The window opens on the top of the current hour and runs [windowHours]
    // hours ahead, so hour marks line up with the ruler.
    val windowStartSec = nowSec / 3600 * 3600
    val windowMs = windowHours * 3_600_000L
    val nameWidth = if (tv) 160.dp else 110.dp

    Column(modifier = Modifier.fillMaxSize()) {
        TimeRuler(
            windowStartSec = windowStartSec,
            windowHours = windowHours,
            nowSec = nowSec,
            startPadding = nameWidth
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { onFavoritesOnly(!favoritesOnly) },
                    label = { Text("★ Favourites") }
                )
            }
            if (groups.isNotEmpty()) {
                item {
                    FilterChip(
                        selected = group == null,
                        onClick = { onGroup(null) },
                        label = { Text("All (${channels.size})") }
                    )
                }
                items(groups, key = { it }) { g ->
                    FilterChip(
                        selected = group == g,
                        onClick = { onGroup(if (group == g) null else g) },
                        label = { Text(g) }
                    )
                }
            }
        }

        if (channels.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No channels", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(channels, key = { it.id }) { channel ->
                    GuideRow(
                        channel = channel,
                        programs = cache.programs[channel.streamId.toString()].orEmpty(),
                        windowStartSec = windowStartSec,
                        windowMs = windowMs,
                        nowSec = nowSec,
                        tv = tv,
                        onPlay = { onPlay(channel) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(
    windowStartSec: Long,
    windowHours: Int,
    nowSec: Long,
    startPadding: Dp
) {
    // Clamp away from exactly 0: Modifier.weight requires a positive weight,
    // and nowFrac is 0.0 when "now" sits on the window's opening hour mark.
    val nowFrac = ((nowSec - windowStartSec) / 3600f).coerceIn(0f, 1f).coerceAtLeast(0.0001f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(14.dp)) {
            repeat(windowHours) { h ->
                Box(Modifier.weight(1f)) {
                    Text(
                        EpgParser.formatHm(windowStartSec + h * 3600L),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(14.dp)) {
            // 30-minute ticks: alternate the surface so half-hour boundaries show.
            Row(Modifier.fillMaxSize()) {
                repeat(windowHours * 2) { half ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .background(
                                if (half % 2 == 1) {
                                    MaterialTheme.colorScheme.outlineVariant
                                } else {
                                    Color.Transparent
                                }
                            )
                    )
                }
            }
            // NOW marker.
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(nowFrac, fill = false).fillMaxHeight())
                if (nowFrac > 0f && nowFrac < 1f) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.weight(1f - nowFrac, fill = false).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programs: List<EpgProgram>,
    windowStartSec: Long,
    windowMs: Long,
    nowSec: Long,
    tv: Boolean,
    onPlay: () -> Unit
) {
    val nameWidth = if (tv) 160.dp else 110.dp
    val rowHeight = if (tv) 64.dp else 52.dp
    val now = programs.firstOrNull { it.startEpochSec <= nowSec && nowSec < it.endEpochSec }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(vertical = 1.dp)
            .clickable { onPlay() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(nameWidth)
                .padding(horizontal = 8.dp)
        ) {
            Column {
                Text(
                    channel.name,
                    style = if (tv) {
                        MaterialTheme.typography.bodyLarge
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (now != null) {
                    Text(
                        "${EpgParser.formatHm(now.startEpochSec)}–" +
                            "${EpgParser.formatHm(now.endEpochSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val windowStartMs = windowStartSec * 1000L
            val windowEndMs = windowStartMs + windowMs
            for (p in programs) {
                val pStartMs = p.startEpochSec * 1000L
                val pEndMs = p.endEpochSec * 1000L
                if (pEndMs <= windowStartMs || pStartMs >= windowEndMs) continue
                val leftMs = maxOf(pStartMs, windowStartMs) - windowStartMs
                val wMs = minOf(pEndMs, windowEndMs) - maxOf(pStartMs, windowStartMs)
                if (leftMs > 0) {
                    Spacer(Modifier.weight(leftMs / windowMs.toFloat(), fill = false))
                }
                val isNow = now != null && p.startEpochSec == now.startEpochSec
                // fill=false: the block gets exactly its proportional slice of the
                // window (normalised weights would stretch gaps away).
                Box(
                    modifier = Modifier
                        .weight(wMs / windowMs.toFloat(), fill = false)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isNow) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .clickable { onPlay() }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter ||
                                    event.key == Key.NumPadEnter)
                            ) {
                                onPlay()
                                true
                            } else {
                                false
                            }
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Text(
                            EpgParser.formatHm(p.startEpochSec),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = if (isNow) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            p.title,
                            style = if (tv) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                            maxLines = if (tv) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isNow) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredNote(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CenteredLoading(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(bottom = 12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
