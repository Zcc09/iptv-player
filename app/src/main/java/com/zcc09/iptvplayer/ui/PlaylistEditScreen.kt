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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.core.Playlist
import com.zcc09.iptvplayer.core.PlaylistType
import com.zcc09.iptvplayer.core.Repo
import kotlinx.coroutines.launch

private val INTERVALS = listOf(15, 30, 60, 180, 360, 720, 1440)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistEditScreen(playlistId: String?) {
    val existing = remember(playlistId) { playlistId?.let { Repo.playlist(it) } }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: PlaylistType.M3U) }
    var autoRefresh by remember { mutableStateOf(existing?.autoRefresh ?: true) }
    var interval by remember { mutableIntStateOf(existing?.refreshIntervalMinutes ?: 360) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    fun build(): Playlist = Playlist(
        id = existing?.id ?: Repo.newId(),
        name = name.trim().ifBlank { if (type == PlaylistType.XTREAM) "Xtream account" else "M3U playlist" },
        type = type,
        url = url.trim(),
        username = username.trim(),
        password = password,
        autoRefresh = autoRefresh,
        refreshIntervalMinutes = interval,
        lastRefresh = existing?.lastRefresh ?: 0L,
        lastRefreshOk = existing?.lastRefreshOk ?: false,
        lastError = "",
        channelCount = existing?.channelCount ?: 0
    )

    val valid = url.isNotBlank() && (type == PlaylistType.M3U || username.isNotBlank())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add playlist" else "Edit playlist") },
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
            Text("Source type", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row {
                FilterChip(
                    selected = type == PlaylistType.M3U,
                    onClick = { type = PlaylistType.M3U },
                    label = { Text("M3U link") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = type == PlaylistType.XTREAM,
                    onClick = { type = PlaylistType.XTREAM },
                    label = { Text("Xtream (XC)") }
                )
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = {
                    Text(if (type == PlaylistType.M3U) "M3U playlist URL" else "Server URL (host or http://host:port)")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (type == PlaylistType.XTREAM) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto refresh playlist", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Re-download the channel list in the background so new channels appear on their own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
            }

            if (autoRefresh) {
                Spacer(Modifier.height(8.dp))
                Text("Refresh every", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    INTERVALS.take(4).forEach { minutes ->
                        FilterChip(
                            selected = interval == minutes,
                            onClick = { interval = minutes },
                            label = { Text(intervalLabel(minutes)) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    INTERVALS.drop(4).forEach { minutes ->
                        FilterChip(
                            selected = interval == minutes,
                            onClick = { interval = minutes },
                            label = { Text(intervalLabel(minutes)) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = valid && !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val probe = build()
                            val result = Repo.fetch(probe)
                            testResult = if (result.ok) {
                                "Connected — ${result.channels.size} channels found"
                            } else {
                                "Failed: ${result.error}"
                            }
                            testing = false
                        }
                    }
                ) { Text("Test connection") }
                Spacer(Modifier.width(8.dp))
                if (testing) CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
            }

            testResult?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it.startsWith("Connected")) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                enabled = valid,
                onClick = {
                    val playlist = build()
                    if (existing == null) Repo.add(playlist) else Repo.update(playlist)
                    Repo.postStatus("Saved ${playlist.name} — loading channels…")
                    Repo.refreshAsync(playlist.id)
                    Nav.pop()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (existing == null) "Add playlist" else "Save changes")
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
