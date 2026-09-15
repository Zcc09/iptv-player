package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.core.AppUiMode
import com.zcc09.iptvplayer.core.Playlist
import com.zcc09.iptvplayer.core.PlaylistType
import com.zcc09.iptvplayer.core.Repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen() {
    val playlists by Repo.playlists.collectAsState()
    val channels by Repo.channels.collectAsState()
    val busy by Repo.busy.collectAsState()
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Internet TV Player") },
                actions = {
                    TextButton(onClick = {
                        Repo.setUiMode(AppUiMode.TV)
                        Repo.postStatus("Switched to Android TV & Gamepad mode")
                    }) {
                        Text("📺 TV Mode")
                    }
                    IconButton(onClick = {
                        Repo.postStatus("Refreshing all playlists…")
                        Repo.refreshAllAsync()
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh all") }
                    IconButton(onClick = { Nav.push(Screen.Settings) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { Nav.push(Screen.EditPlaylist(null)) }) {
                Icon(Icons.Default.Add, contentDescription = "Add playlist")
            }
        }
    ) { padding ->
        if (playlists.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        channelCount = channels[playlist.id]?.size ?: playlist.channelCount,
                        refreshing = busy.contains(playlist.id),
                        onOpen = { Nav.push(Screen.Channels(playlist.id)) },
                        onRefresh = { Repo.refreshAsync(playlist.id) },
                        onEdit = { Nav.push(Screen.EditPlaylist(playlist.id)) },
                        onDelete = { pendingDelete = playlist }
                    )
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    pendingDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove playlist?") },
            text = { Text("\"${playlist.name}\" and its cached channel list will be deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    Repo.delete(playlist.id)
                    Repo.postStatus("Removed ${playlist.name}")
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No playlists yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add an M3U playlist link, or an Xtream Codes (XC) server with your username and password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { Nav.push(Screen.EditPlaylist(null)) }) { Text("Add your first playlist") }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    channelCount: Int,
    refreshing: Boolean,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(if (playlist.type == PlaylistType.XTREAM) "XTREAM" else "M3U")
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$channelCount channels",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("Last update: ")
                    append(if (playlist.lastRefresh == 0L) "never" else formatTime(playlist.lastRefresh))
                    append("  •  auto-refresh ")
                    append(if (playlist.autoRefresh) "every ${intervalLabel(playlist.refreshIntervalMinutes)}" else "off")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (playlist.lastError.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Last error: ${playlist.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(millis))

fun intervalLabel(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 && minutes < 1440 -> "${minutes / 60}h"
    minutes == 1440 -> "24h"
    else -> "${minutes}m"
}
