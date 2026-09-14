package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.core.Channel
import com.zcc09.iptvplayer.core.Repo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(playlistId: String) {
    val playlist = remember(playlistId) { Repo.playlist(playlistId) }
    val channelsMap by Repo.channels.collectAsState()
    val favorites by Repo.favorites.collectAsState()
    val busy by Repo.busy.collectAsState()

    val all = channelsMap[playlistId].orEmpty()
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }

    val groups = remember(all) {
        all.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val shown = remember(all, query, group, favoritesOnly, favorites) {
        all.asSequence()
            .filter { group == null || it.group == group }
            .filter { !favoritesOnly || favorites.contains(it.id) }
            .filter {
                query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.group.contains(query, ignoreCase = true)
            }
            .toList()
    }

    val refreshing = busy.contains(playlistId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "Channels") },
                navigationIcon = {
                    IconButton(onClick = { Nav.pop() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(20.dp).height(20.dp).padding(end = 4.dp)
                        )
                    }
                    IconButton(onClick = {
                        Repo.postStatus("Refreshing ${playlist?.name ?: "playlist"}…")
                        Repo.refreshAsync(playlistId)
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search channels") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = !favoritesOnly },
                        label = { Text("★ Favourites") }
                    )
                }
                if (groups.isNotEmpty()) {
                    item {
                        FilterChip(
                            selected = group == null,
                            onClick = { group = null },
                            label = { Text("All (${all.size})") }
                        )
                    }
                    items(groups, key = { it }) { g ->
                        FilterChip(
                            selected = group == g,
                            onClick = { group = if (group == g) null else g },
                            label = { Text(g) }
                        )
                    }
                }
            }

            Text(
                text = "${shown.size} of ${all.size} channels" +
                    (playlist?.lastRefresh?.takeIf { it > 0 }?.let { "  •  updated ${formatTime(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )

            if (all.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No channels cached yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        playlist?.lastError?.takeIf { it.isNotBlank() }
                            ?: "Tap refresh to download the channel list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (playlist?.lastError.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = { Repo.refreshAsync(playlistId) }) { Text("Refresh now") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(shown, key = { it.id }) { channel ->
                        ChannelRow(
                            channel = channel,
                            favorite = favorites.contains(channel.id),
                            onToggleFavorite = { Repo.toggleFavorite(channel.id) },
                            onPlay = { Nav.push(Screen.Player(channel)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteImage(
            url = channel.logo,
            contentDescription = channel.name,
            size = 44.dp,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            if (channel.group.isNotBlank()) {
                Text(
                    channel.group,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (favorite) "Remove favourite" else "Add favourite",
                tint = if (favorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
    }
}
