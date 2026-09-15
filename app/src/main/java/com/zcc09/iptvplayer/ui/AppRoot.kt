package com.zcc09.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zcc09.iptvplayer.core.Repo
import com.zcc09.iptvplayer.core.TvDetector
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
    }

    BackHandler(enabled = Nav.canGoBack) { Nav.pop() }
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
