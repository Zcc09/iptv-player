package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.core.EpgStore
import com.zcc09.iptvplayer.core.Playlist
import com.zcc09.iptvplayer.core.Repo

/**
 * What a viewer picks after choosing a playlist: the kind of thing they want to
 * watch, not a list of channels.
 *
 * Live is the one most people came for, so it is the loud card; the rest are
 * quieter and only appear when the playlist can actually serve them (a plain
 * M3U link has no film catalogue, and a provider with no feed has no guide).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModeScreen(playlistId: String) {
    val playlist: Playlist? = remember(playlistId) { Repo.playlist(playlistId) }
    val channels = remember(playlistId) { Repo.channelsOf(playlistId) }
    val vod = remember(playlistId) { Repo.supportsVod(playlistId) }
    val guide = remember(playlistId) { Repo.hasEpg(playlistId) }
    val favorites by Repo.favorites.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "Playlist") },
                navigationIcon = {
                    IconButton(onClick = { Nav.pop() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to playlists")
                    }
                }
            )
        },
        floatingActionButton = {
            // Settings live here as well as on the playlist list: this is the
            // page a viewer is on when something about the playlist is wrong.
            FloatingActionButton(onClick = { Nav.push(Screen.Settings) }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "${channels.size} channels" +
                    if (favorites.isEmpty()) "" else "  ·  ${favorites.size} favourites",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            ModeCard(
                title = "Live TV",
                detail = "Watch the channel list with a preview",
                primary = true,
                onClick = { Nav.push(Screen.Channels(playlistId, ChannelsTab.LIVE)) }
            )
            if (vod) {
                Spacer(Modifier.height(10.dp))
                ModeCard(
                    title = "Movies",
                    detail = "Films from this provider",
                    onClick = { Nav.push(Screen.Channels(playlistId, ChannelsTab.MOVIE)) }
                )
                Spacer(Modifier.height(10.dp))
                ModeCard(
                    title = "Shows",
                    detail = "Series and episodes",
                    onClick = { Nav.push(Screen.Channels(playlistId, ChannelsTab.SERIES)) }
                )
            }
            if (guide) {
                Spacer(Modifier.height(10.dp))
                ModeCard(
                    title = "TV guide",
                    detail = "What is on now, across channels",
                    onClick = { Nav.push(Screen.Channels(playlistId, ChannelsTab.GUIDE)) }
                )
            }
            Spacer(Modifier.height(10.dp))
            ModeCard(
                title = "Recordings",
                detail = "Captures saved on this device",
                onClick = { Nav.push(Screen.Channels(playlistId, ChannelsTab.RECORDINGS)) }
            )
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    detail: String,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val background = if (primary) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val titleColor = if (primary) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(background, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 20.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (primary) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (primary) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            )
        }
    }
}
