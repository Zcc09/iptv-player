package com.zcc09.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.BuildConfig
import com.zcc09.iptvplayer.core.Repo
import com.zcc09.iptvplayer.core.TvDetector
import com.zcc09.iptvplayer.core.Updater
import com.zcc09.iptvplayer.core.UpdateState
import kotlinx.coroutines.delay

@Composable
fun AppRoot() {
    val playRequest by Repo.playRequest.collectAsState()
    val status by Repo.status.collectAsState()
    val uiModePref by Repo.uiMode.collectAsState()
    val gamepadActive by TvDetector.gamepadDetected.collectAsState()

    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val isTvMode = remember(configuration, uiModePref, gamepadActive) {
        TvDetector.isTvMode(context, configuration, uiModePref, gamepadActive)
    }

    // Commands (UI or debug hooks) ask for a channel to start playing.
    LaunchedEffect(playRequest) {
        val request = playRequest ?: return@LaunchedEffect
        Repo.consumePlayRequest()
        Nav.push(Screen.Player(request.channel, request.urlOverride))
    }

    // Check for updates on startup
    LaunchedEffect(Unit) {
        Updater.checkForUpdate(currentVersion = BuildConfig.VERSION_NAME, isManual = false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val screen = Nav.current) {
            is Screen.Home -> {
                if (isTvMode) {
                    TvHomeScreen()
                } else {
                    PlaylistsScreen()
                }
            }
            is Screen.EditPlaylist -> PlaylistEditScreen(screen.playlistId)
            is Screen.Channels -> ChannelsScreen(screen.playlistId)
            is Screen.Player -> PlayerScreen(screen.channel, screen.urlOverride)
            is Screen.Settings -> SettingsScreen()
        }

        StatusBanner(text = status, modifier = Modifier.align(Alignment.BottomCenter))
        UpdateDialog()
    }

    BackHandler(enabled = Nav.canGoBack) { Nav.pop() }
}

@Composable
private fun UpdateDialog() {
    val context = LocalContext.current
    val updateState by Updater.state.collectAsState()

    when (val state = updateState) {
        is UpdateState.Available -> {
            AlertDialog(
                onDismissRequest = { Updater.dismissUpdate() },
                title = { Text("🚀 Update Available: ${state.info.versionName}") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = state.info.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (state.info.apkSize > 0) {
                            Spacer(Modifier.height(4.dp))
                            val mb = state.info.apkSize / (1024f * 1024f)
                            Text(
                                text = "Size: ${"%.1f".format(mb)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.info.notes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Release notes:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = state.info.notes.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { Updater.startDownload(context, state.info) }) {
                        Text("Download & Install")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { Updater.dismissUpdate() }) {
                        Text("Later")
                    }
                }
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = { /* Prevent accidental cancel during download */ },
                title = { Text("Downloading Update…") },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = if (state.percent >= 0) "${state.percent}%" else "Downloading…",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        if (state.percent >= 0) {
                            LinearProgressIndicator(
                                progress = { state.percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(10.dp))
                        val downMb = state.downloadedBytes / (1024f * 1024f)
                        val totalMb = state.totalBytes / (1024f * 1024f)
                        Text(
                            text = if (state.totalBytes > 0) "${"%.1f".format(downMb)} MB / ${"%.1f".format(totalMb)} MB" else "${"%.1f".format(downMb)} MB downloaded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { Updater.dismissUpdate() },
                title = { Text("Update Ready to Install") },
                text = {
                    Text("The update has been downloaded. Press Install to update IPTV Player now.")
                },
                confirmButton = {
                    Button(onClick = { Updater.installApk(context, state.apkFile) }) {
                        Text("Install")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { Updater.dismissUpdate() }) {
                        Text("Later")
                    }
                }
            )
        }

        is UpdateState.Error -> {
            AlertDialog(
                onDismissRequest = { Updater.dismissUpdate() },
                title = { Text("Update Check") },
                text = { Text(state.message) },
                confirmButton = {
                    Button(onClick = { Updater.dismissUpdate() }) {
                        Text("OK")
                    }
                }
            )
        }

        else -> {}
    }
}

@Composable
private fun StatusBanner(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    LaunchedEffect(text) {
        delay(4500)
        Repo.clearStatus()
    }
    Surface(
        modifier = modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 6.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
