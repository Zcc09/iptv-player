package com.zcc09.iptvplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.BuildConfig
import com.zcc09.iptvplayer.core.AppUiMode
import com.zcc09.iptvplayer.core.CastMode
import com.zcc09.iptvplayer.core.Net
import com.zcc09.iptvplayer.core.RelayManager
import com.zcc09.iptvplayer.core.Repo
import com.zcc09.iptvplayer.core.Updater
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val castMode by Repo.castMode.collectAsState()
    val uiMode by Repo.uiMode.collectAsState()
    val showNavHints by Repo.showNavHints.collectAsState()
    val playlists by Repo.playlists.collectAsState()
    var userAgent by remember { mutableStateOf(Repo.userAgent) }
    var relayStatus by remember { mutableStateOf(RelayManager.status()) }

    fun refreshRelayStatus() {
        relayStatus = RelayManager.status()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { Nav.pop() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Interface & Controls", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Select whether to use the 10-foot Android TV & Remote interface or standard Mobile Touch interface.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiMode == AppUiMode.AUTO,
                    onClick = {
                        Repo.setUiMode(AppUiMode.AUTO)
                        Repo.postStatus("Interface mode: Auto-detect")
                    },
                    label = { Text("Auto (Widescreen/TV)") }
                )
                FilterChip(
                    selected = uiMode == AppUiMode.TV,
                    onClick = {
                        Repo.setUiMode(AppUiMode.TV)
                        Repo.postStatus("Interface mode: Android TV & Remote")
                    },
                    label = { Text("Android TV & Remote") }
                )
                FilterChip(
                    selected = uiMode == AppUiMode.MOBILE,
                    onClick = {
                        Repo.setUiMode(AppUiMode.MOBILE)
                        Repo.postStatus("Interface mode: Mobile Touch")
                    },
                    label = { Text("Mobile Touch") }
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Controller & Remote Navigation Hints", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Show button shortcut prompts at the bottom of the TV screen and in the video player OSD.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showNavHints,
                    onCheckedChange = { Repo.setShowNavHints(it) }
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                Repo.setTvSetupDismissed(false)
                Repo.postStatus("TV & Gamepad guide will show on next TV launch")
            }) {
                Text("Show TV & Gamepad Guide on next launch")
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Playback", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = userAgent,
                onValueChange = { userAgent = it },
                label = { Text("User-Agent for stream requests") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Some providers only answer to a specific player. IPTVPlayer/1.0 (Android) works for most Dispatcharr/Xtream servers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                Repo.setUserAgent(userAgent)
                Repo.postStatus("User-Agent saved")
            }) { Text("Save User-Agent") }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Casting", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Chromecast cannot play raw MPEG-TS, which is what most IPTV streams are. " +
                    "The relay converts the stream to HLS on this phone so any Chromecast, " +
                    "Google TV or Android TV can play it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = castMode == CastMode.AUTO,
                    onClick = { Repo.setCastMode(CastMode.AUTO) },
                    label = { Text("Auto") }
                )
                FilterChip(
                    selected = castMode == CastMode.DIRECT,
                    onClick = { Repo.setCastMode(CastMode.DIRECT) },
                    label = { Text("Direct") }
                )
                FilterChip(
                    selected = castMode == CastMode.RELAY,
                    onClick = { Repo.setCastMode(CastMode.RELAY) },
                    label = { Text("Relay") }
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "HLS relay: $relayStatus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Phone address for the TV: ${Net.lanIp() ?: "not on Wi-Fi"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row {
                TextButton(onClick = { refreshRelayStatus() }) { Text("Refresh status") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    RelayManager.stop()
                    refreshRelayStatus()
                    Repo.postStatus("Relay stopped")
                }) { Text("Stop relay") }
            }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Playlists", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "${playlists.size} saved. Auto-refresh runs in the background at the interval " +
                    "set on each playlist (minimum 15 minutes).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                Repo.postStatus("Refreshing all playlists…")
                Repo.refreshAllAsync()
            }) { Text("Refresh all now") }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Updates", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Current version: v${BuildConfig.VERSION_NAME}\n" +
                    "IPTV Player checks GitHub releases for updates. When a new version is released, " +
                    "it can be downloaded and installed directly from the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    Repo.postStatus("Checking GitHub for updates…")
                    Updater.checkForUpdate(BuildConfig.VERSION_NAME, isManual = true)
                }
            }) {
                Text("Check for updates now")
            }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("About", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "IPTV Player ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                    "M3U playlists and Xtream Codes accounts, Media3/ExoPlayer playback, " +
                    "Chromecast support, background auto-refresh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
