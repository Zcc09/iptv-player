package com.zcc09.iptvplayer.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcc09.iptvplayer.core.AppUiMode
import com.zcc09.iptvplayer.core.Channel
import com.zcc09.iptvplayer.core.Playlist
import com.zcc09.iptvplayer.core.Repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 10-foot Android TV & Gamepad home interface.
 * Intuitive two-pane widescreen layout:
 * - Left rail: Categories, Favourites, and Playlists
 * - Right area: Dynamic Channel Spotlight and D-pad navigable Channel Grid
 */
@Composable
fun TvHomeScreen() {
    val playlists by Repo.playlists.collectAsState()
    val channelsMap by Repo.channels.collectAsState()
    val favorites by Repo.favorites.collectAsState()
    val busy by Repo.busy.collectAsState()
    val tvSetupDismissed by Repo.tvSetupDismissed.collectAsState()

    var showTvSetupDialog by remember { mutableStateOf(!tvSetupDismissed) }

    // Active playlist selection
    var selectedPlaylistIndex by remember { mutableStateOf(0) }
    val activePlaylist: Playlist? = playlists.getOrNull(selectedPlaylistIndex)
        ?: playlists.firstOrNull()
    val activePlaylistId = activePlaylist?.id.orEmpty()
    val allChannels = channelsMap[activePlaylistId].orEmpty()

    // Categories in active playlist
    val groups = remember(allChannels) {
        allChannels.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    }

    // Selected category filter: null = all channels, "__FAV__" = favorites
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Filtered channels list
    val filteredChannels = remember(allChannels, selectedGroup, searchQuery, favorites) {
        allChannels.asSequence()
            .filter {
                when (selectedGroup) {
                    null -> true
                    "__FAV__" -> favorites.contains(it.id)
                    else -> it.group == selectedGroup
                }
            }
            .filter {
                searchQuery.isBlank() ||
                    it.name.contains(searchQuery, ignoreCase = true) ||
                    it.group.contains(searchQuery, ignoreCase = true)
            }
            .toList()
    }

    // Focused channel for the Spotlight preview
    var spotlightChannel by remember { mutableStateOf<Channel?>(null) }
    LaunchedEffect(filteredChannels) {
        spotlightChannel = filteredChannels.firstOrNull()
    }

    // Time ticker for top bar
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
        while (true) {
            currentTime = format.format(Date())
            delay(15_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_Y -> {
                            // Quick toggle favorite on currently spotlighted channel
                            spotlightChannel?.let {
                                Repo.toggleFavorite(it.id)
                                val action = if (favorites.contains(it.id)) "Removed from" else "Added to"
                                Repo.postStatus("$action Favourites: ${it.name}")
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ------------------------------------------------------------- TOP BAR
            TvTopBar(
                activePlaylist = activePlaylist,
                channelCount = allChannels.size,
                currentTime = currentTime,
                isRefreshing = busy.contains(activePlaylistId),
                onRefresh = {
                    if (activePlaylist != null) {
                        Repo.postStatus("Refreshing ${activePlaylist.name}…")
                        Repo.refreshAsync(activePlaylist.id)
                    } else {
                        Repo.refreshAllAsync()
                    }
                },
                onSwitchToMobile = {
                    Repo.setUiMode(AppUiMode.MOBILE)
                    Repo.postStatus("Switched to Mobile touch mode")
                },
                onOpenSettings = {
                    Nav.push(Screen.Settings)
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

            if (playlists.isEmpty()) {
                TvEmptyPlaylistsView(
                    onAddPlaylist = { Nav.push(Screen.EditPlaylist(null)) },
                    onSwitchToMobile = { Repo.setUiMode(AppUiMode.MOBILE) }
                )
            } else {
                // --------------------------------------------------------- 2-PANE BODY
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // Left Navigation Rail (Sidebar)
                    TvSidebar(
                        playlists = playlists,
                        activePlaylistIndex = selectedPlaylistIndex,
                        onSelectPlaylist = { selectedPlaylistIndex = it },
                        totalChannels = allChannels.size,
                        favoriteCount = allChannels.count { favorites.contains(it.id) },
                        selectedGroup = selectedGroup,
                        groups = groups,
                        onSelectGroup = { selectedGroup = it },
                        modifier = Modifier
                            .width(260.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF0F141E))
                    )

                    // Vertical divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.06f))
                    )

                    // Right Content Area: Spotlight Header + Channel Grid
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        // Spotlight Banner
                        spotlightChannel?.let { channel ->
                            TvChannelSpotlight(
                                channel = channel,
                                isFavorite = favorites.contains(channel.id),
                                onPlay = { Nav.push(Screen.Player(channel)) },
                                onToggleFavorite = {
                                    Repo.toggleFavorite(channel.id)
                                    val action = if (favorites.contains(channel.id)) "Removed from" else "Added to"
                                    Repo.postStatus("$action Favourites: ${channel.name}")
                                }
                            )
                            Spacer(Modifier.height(14.dp))
                        }

                        // Search & Section Title
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = when (selectedGroup) {
                                    null -> "All Channels (${filteredChannels.size})"
                                    "__FAV__" -> "★ Favourites (${filteredChannels.size})"
                                    else -> "$selectedGroup (${filteredChannels.size})"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Spacer(Modifier.weight(1f))

                            // Quick Search Input
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search channels (Press [X])", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray)
                                        }
                                    }
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF192131),
                                    unfocusedContainerColor = Color(0xFF131924),
                                    focusedBorderColor = TvFocusedBorderColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier
                                    .width(280.dp)
                                    .height(48.dp)
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        // Channel Grid
                        if (filteredChannels.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("No channels found", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        if (searchQuery.isNotBlank()) "No channels match \"$searchQuery\""
                                        else "This category does not have any channels.",
                                        color = Color.DarkGray
                                    )
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 160.dp),
                                contentPadding = PaddingValues(bottom = 40.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredChannels, key = { it.id }) { channel ->
                                    TvChannelCard(
                                        channel = channel,
                                        isFavorite = favorites.contains(channel.id),
                                        onFocus = { spotlightChannel = channel },
                                        onPlay = { Nav.push(Screen.Player(channel)) },
                                        onToggleFavorite = {
                                            Repo.toggleFavorite(channel.id)
                                            val action = if (favorites.contains(channel.id)) "Removed from" else "Added to"
                                            Repo.postStatus("$action Favourites: ${channel.name}")
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------------- BOTTOM PROMPT BAR
            TvBottomPromptBar()
        }

        // ---------------------------------------------------------------- FIRST-TIME SETUP DIALOG
        if (showTvSetupDialog) {
            TvWelcomeSetupDialog(
                onDismiss = {
                    showTvSetupDialog = false
                    Repo.setTvSetupDismissed(true)
                },
                onSwitchToMobile = {
                    showTvSetupDialog = false
                    Repo.setTvSetupDismissed(true)
                    Repo.setUiMode(AppUiMode.MOBILE)
                }
            )
        }
    }
}

/**
 * Top bar displaying brand, active playlist info, clock, and quick navigation.
 */
@Composable
private fun TvTopBar(
    activePlaylist: Playlist?,
    channelCount: Int,
    currentTime: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSwitchToMobile: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        // App brand & TV Badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "📺 IPTV PLAYER",
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = Color.White
            )
            Spacer(Modifier.width(8.dp))
            TvBadge(text = "TV EDITION", color = Color(0xFF38BDF8))
        }

        Spacer(Modifier.width(20.dp))

        // Active playlist & channel count (contains "CI M3U playlist" & "channels" when seeded)
        if (activePlaylist != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF161D2B)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = activePlaylist.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = Color(0xFFE2E8F0)
                    )
                    Text(
                        text = " • $channelCount channels",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Current time
        if (currentTime.isNotBlank()) {
            Text(
                text = currentTime,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(end = 16.dp)
            )
        }

        // Refresh indicator / button
        if (isRefreshing) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(22.dp)
                    .padding(end = 8.dp)
            )
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh channels", tint = Color(0xFF94A3B8))
            }
        }

        // Switch to Mobile Mode button
        TvTopActionPill(
            label = "📱 Mobile UI",
            onClick = onSwitchToMobile
        )

        Spacer(Modifier.width(8.dp))

        // Settings Button
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF94A3B8))
        }
    }
}

@Composable
private fun TvTopActionPill(label: String, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isFocused) TvFocusedBorderColor else Color(0xFF1E2838),
        border = if (isFocused) BorderStroke(2.dp, Color.White) else null,
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Color.Black else Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * Sidebar navigation rail.
 */
@Composable
private fun TvSidebar(
    playlists: List<Playlist>,
    activePlaylistIndex: Int,
    onSelectPlaylist: (Int) -> Unit,
    totalChannels: Int,
    favoriteCount: Int,
    selectedGroup: String?,
    groups: List<String>,
    onSelectGroup: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.padding(vertical = 12.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Multi-playlist switcher if more than 1 playlist
        if (playlists.size > 1) {
            item {
                Text(
                    text = "PLAYLISTS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            items(playlists.indices.toList(), key = { "pl_$it" }) { idx ->
                val pl = playlists[idx]
                TvNavButton(
                    label = pl.name,
                    iconText = if (pl.type.name == "XTREAM") "⚡" else "📋",
                    selected = activePlaylistIndex == idx,
                    onClick = { onSelectPlaylist(idx) }
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(Modifier.height(8.dp))
            }
        }

        item {
            Text(
                text = "NAVIGATION",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        item {
            TvNavButton(
                label = "All Channels",
                iconText = "📺",
                selected = selectedGroup == null,
                badgeText = totalChannels.toString(),
                onClick = { onSelectGroup(null) }
            )
        }

        item {
            TvNavButton(
                label = "Favourites",
                iconText = "★",
                selected = selectedGroup == "__FAV__",
                badgeText = favoriteCount.toString(),
                onClick = { onSelectGroup("__FAV__") }
            )
        }

        if (groups.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "CATEGORIES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            items(groups, key = { "grp_$it" }) { groupName ->
                TvNavButton(
                    label = groupName,
                    iconText = "📁",
                    selected = selectedGroup == groupName,
                    onClick = { onSelectGroup(groupName) }
                )
            }
        }
    }
}

/**
 * Large Spotlight Hero Card showing rich info about the currently selected channel.
 */
@Composable
private fun TvChannelSpotlight(
    channel: Channel,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131B29)),
        border = BorderStroke(1.dp, Color(0xFF26334D)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF172033), Color(0xFF0F1522))
                    )
                )
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = channel.logo,
                    contentDescription = channel.name,
                    size = 68.dp,
                    modifier = Modifier
                        .background(Color(0xFF1C2638), RoundedCornerShape(10.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                )

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = channel.name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isFavorite) {
                            Spacer(Modifier.width(8.dp))
                            Text(text = "★ FAVOURITE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24))
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (channel.group.isNotBlank()) {
                            Text(
                                text = channel.group,
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        TvBadge(text = "LIVE", color = Color(0xFFEF4444))
                        if (channel.name.contains("4K", ignoreCase = true)) {
                            TvBadge(text = "4K UHD", color = Color(0xFF10B981))
                        } else {
                            TvBadge(text = "HD", color = Color(0xFF0284C7))
                        }
                        if (channel.url.contains(".m3u8", ignoreCase = true)) {
                            TvBadge(text = "HLS", color = Color(0xFF8B5CF6))
                        } else {
                            TvBadge(text = "TS", color = Color(0xFFF59E0B))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemoteButtonHint(buttonLabel = "OK / A", actionName = "Play Fullscreen")
                        Spacer(Modifier.width(12.dp))
                        RemoteButtonHint(
                            buttonLabel = "Y",
                            actionName = if (isFavorite) "Remove Favourite" else "Add Favourite",
                            buttonColor = Color(0xFF475569)
                        )
                        Spacer(Modifier.width(12.dp))
                        RemoteButtonHint(buttonLabel = "X", actionName = "Search", buttonColor = Color(0xFF475569))
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Big focusable Play Button
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = TvFocusedBorderColor),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text("PLAY", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

/**
 * Individual channel card in the TV grid.
 */
@Composable
private fun TvChannelCard(
    channel: Channel,
    isFavorite: Boolean,
    onFocus: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    TvFocusableCard(
        onClick = onPlay,
        onSpecialKey = { keyCode ->
            if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
                onToggleFavorite()
                true
            } else false
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(138.dp)
    ) { isFocused ->
        LaunchedEffect(isFocused) {
            if (isFocused) onFocus()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RemoteImage(
                    url = channel.logo,
                    contentDescription = channel.name,
                    size = 40.dp,
                    modifier = Modifier.background(Color(0xFF1B2332), RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.weight(1f))
                if (isFavorite) {
                    Text(text = "★", fontSize = 16.sp, color = Color(0xFFFBBF24))
                }
            }

            Spacer(Modifier.height(6.dp))

            Column {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (channel.group.isNotBlank()) {
                    Text(
                        text = channel.group,
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Bottom bar showing controller & remote button shortcuts.
 */
@Composable
private fun TvBottomPromptBar() {
    Surface(
        color = Color(0xFF0A0D13),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 16.dp)
        ) {
            RemoteButtonHint(buttonLabel = "D-PAD", actionName = "Navigate")
            Spacer(Modifier.width(16.dp))
            RemoteButtonHint(buttonLabel = "OK / (A)", actionName = "Play Channel")
            Spacer(Modifier.width(16.dp))
            RemoteButtonHint(buttonLabel = "(Y)", actionName = "Toggle Favourite")
            Spacer(Modifier.width(16.dp))
            RemoteButtonHint(buttonLabel = "(X)", actionName = "Quick Search")
            Spacer(Modifier.width(16.dp))
            RemoteButtonHint(buttonLabel = "(B) / Back", actionName = "Return")
        }
    }
}

/**
 * First-Time Setup / TV Welcome Guide modal dialog.
 */
@Composable
private fun TvWelcomeSetupDialog(
    onDismiss: () -> Unit,
    onSwitchToMobile: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎮 Android TV & Remote Mode")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Widescreen display or remote/gamepad input detected! The 10-foot TV interface is now active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Text(
                    "Controls Reference:",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                RemoteButtonHint(buttonLabel = "D-Pad / Left Stick", actionName = "Browse categories and channels smoothly")
                RemoteButtonHint(buttonLabel = "OK / (A) Button", actionName = "Select category or Play channel fullscreen")
                RemoteButtonHint(buttonLabel = "(Y) / Yellow Button", actionName = "Quick toggle channel in Favourites")
                RemoteButtonHint(buttonLabel = "(X) / Search Button", actionName = "Search channels by name or group")
                RemoteButtonHint(buttonLabel = "(B) / Back Button", actionName = "Return to categories or previous screen")
                RemoteButtonHint(buttonLabel = "Channel Up / Down", actionName = "Instant channel zapping while in video player")

                Spacer(Modifier.height(4.dp))
                Text(
                    "You can switch back to Mobile touch mode anytime from the top bar or Settings.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = TvFocusedBorderColor)
            ) {
                Text("Start Watching", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onSwitchToMobile) {
                Text("Use Mobile Touch UI")
            }
        }
    )
}

/**
 * TV-friendly empty state when no playlists have been added yet.
 */
@Composable
private fun TvEmptyPlaylistsView(
    onAddPlaylist: () -> Unit,
    onSwitchToMobile: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = "📺", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Welcome to IPTV Player — TV Edition",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No playlists found. Add an M3U playlist link or Xtream Codes (XC) account to get started.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF94A3B8)
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(
                    onClick = onAddPlaylist,
                    colors = ButtonDefaults.buttonColors(containerColor = TvFocusedBorderColor)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Playlist", fontWeight = FontWeight.Bold, color = Color.Black)
                }

                OutlinedButton(onClick = onSwitchToMobile) {
                    Text("Switch to Mobile Mode")
                }
            }
        }
    }
}
