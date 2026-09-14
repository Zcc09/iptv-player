package com.zcc09.iptvplayer.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zcc09.iptvplayer.cast.CastCtl
import com.zcc09.iptvplayer.core.CastMode
import com.zcc09.iptvplayer.core.Channel
import com.zcc09.iptvplayer.core.Logx
import com.zcc09.iptvplayer.core.RelayManager
import com.zcc09.iptvplayer.core.Repo
import com.zcc09.iptvplayer.core.UrlTools
import com.zcc09.iptvplayer.player.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val ASPECTS = listOf(
    "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
    "Crop" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    "Stretch" to AspectRatioFrameLayout.RESIZE_MODE_FILL
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(channel: Channel, urlOverride: String?) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val url = urlOverride ?: channel.url

    val casting by CastCtl.casting.collectAsState()
    val routes by CastCtl.routes.collectAsState()
    val connectedDevice by CastCtl.device.collectAsState()

    var showCastDialog by remember { mutableStateOf(false) }
    var aspectIndex by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pip by remember { mutableStateOf(false) }

    val exo = remember(channel.id) { Playback.createPlayer(context, Repo.userAgent) }
    val siblings = remember(channel.playlistId) { Repo.channelsOf(channel.playlistId) }
    val pipSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    // ------------------------------------------------------------- player events
    DisposableEffect(channel.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> Logx.i("PLAYBACK_BUFFERING url=$url")
                    Player.STATE_READY -> Logx.i("PLAYBACK_READY url=$url")
                    Player.STATE_ENDED -> Logx.i("PLAYBACK_ENDED url=$url")
                    else -> Logx.i("PLAYBACK_STATE_$playbackState url=$url")
                }
            }

            override fun onRenderedFirstFrame() {
                errorText = null
                Logx.i("PLAYBACK_FIRST_FRAME url=$url")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Logx.i("PLAYBACK_VIDEO_SIZE ${videoSize.width}x${videoSize.height} url=$url")
            }

            override fun onPlayerError(error: PlaybackException) {
                Logx.w("PLAYBACK_ERROR ${error.errorCodeName} :: ${error.message}")
                errorText = error.message ?: error.errorCodeName
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // ------------------------------------------------------------- start playback
    LaunchedEffect(url, casting) {
        if (casting) {
            exo.pause()
            val mode = Repo.castMode.value
            val target = when (mode) {
                CastMode.DIRECT -> Playback.directCastUrl(channel)
                CastMode.RELAY -> withContext(Dispatchers.IO) {
                    RelayManager.ensure(channel, Repo.userAgent)
                }

                CastMode.AUTO -> if (UrlTools.isHls(url)) {
                    url
                } else {
                    withContext(Dispatchers.IO) { RelayManager.ensure(channel, Repo.userAgent) }
                }
            }
            Repo.postStatus("Casting ${channel.name} to the TV")
            CastCtl.load(target, channel.name, channel.logo)
        } else {
            Logx.i("PLAYBACK_OPENING url=$url mime=${Playback.mimeFor(url)}")
            exo.setMediaItem(Playback.mediaItem(url, channel.name, channel.logo))
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    // ------------------------------------------------------------- orientation + pip
    DisposableEffect(Unit) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.requestedOrientation =
                previous?.takeIf { it != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                    ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(activity) {
        if (pipSupported) {
            while (true) {
                pip = activity?.isInPictureInPictureMode == true
                delay(500)
            }
        }
    }

    LaunchedEffect(showCastDialog) {
        if (showCastDialog) CastCtl.startDiscovery(context)
    }

    fun move(offset: Int) {
        if (siblings.isEmpty()) return
        val index = siblings.indexOfFirst { it.id == channel.id }
        if (index < 0) return
        val next = siblings[(index + offset + siblings.size) % siblings.size]
        Nav.replaceTop(Screen.Player(next, null))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    player = exo
                }
            },
            update = { view ->
                view.player = if (casting) CastCtl.localPlayer() else exo
                view.resizeMode = ASPECTS[aspectIndex].second
                view.useController = !pip
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!pip) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    IconButton(onClick = { Nav.pop() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            channel.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            color = Color.White
                        )
                        if (casting) {
                            Text(
                                "Casting to ${connectedDevice.ifBlank { "TV" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        } else if (RelayManager.isRunning) {
                            Text(
                                "HLS relay running on this phone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    IconButton(onClick = { move(-1) }) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous channel")
                    }
                    IconButton(onClick = { move(1) }) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next channel")
                    }
                    TextButton(onClick = { aspectIndex = (aspectIndex + 1) % ASPECTS.size }) {
                        Text(ASPECTS[aspectIndex].first, color = Color.White)
                    }
                    if (pipSupported) {
                        TextButton(onClick = {
                            val act = activity
                            if (act == null) {
                                Logx.w("PIP_ENTER_FAILED no activity")
                                return@TextButton
                            }
                            runCatching {
                                val params = buildPipParams()
                                act.setPictureInPictureParams(params)
                                act.enterPictureInPictureMode(params)
                            }.onFailure { Logx.w("PIP_ENTER_FAILED ${it.message}") }
                        }) { Text("PiP", color = Color.White) }
                    }
                    IconButton(
                        onClick = { showCastDialog = true },
                        enabled = CastCtl.available
                    ) {
                        CastGlyph(
                            color = if (casting) {
                                MaterialTheme.colorScheme.primary
                            } else if (CastCtl.available) {
                                Color.White
                            } else {
                                Color.DarkGray
                            }
                        )
                    }
                }
            }
        }

        errorText?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Playback error", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = {
                    errorText = null
                    exo.prepare()
                    exo.playWhenReady = true
                }) { Text("Retry") }
            }
        }
    }

    if (showCastDialog) {
        AlertDialog(
            onDismissRequest = {
                showCastDialog = false
                CastCtl.stopDiscovery()
            },
            title = { Text("Cast to TV") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (!CastCtl.available) {
                        Text(
                            "Chromecast support needs Google Play Services, which is not " +
                                "available on this device. Local playback still works.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        if (casting) {
                            Text(
                                "Connected to ${connectedDevice.ifBlank { "TV" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = {
                                CastCtl.disconnect()
                                showCastDialog = false
                            }) { Text("Stop casting") }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (routes.isEmpty()) {
                            Text(
                                "Searching for Cast devices on your Wi-Fi…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            routes.forEach { route ->
                                TextButton(
                                    onClick = {
                                        CastCtl.connect(route)
                                        showCastDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(route.name, style = MaterialTheme.typography.bodyLarge)
                                        if (route.description.isNotBlank()) {
                                            Text(
                                                route.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            when (Repo.castMode.value) {
                                CastMode.AUTO ->
                                    "Mode: Auto — HLS streams go straight to the TV, other " +
                                        "formats are re-published as HLS from this phone."

                                CastMode.DIRECT ->
                                    "Mode: Direct — the raw stream URL is sent to the TV " +
                                        "(most TVs cannot play raw MPEG-TS)."

                                CastMode.RELAY ->
                                    "Mode: Relay — this phone converts the stream to HLS and " +
                                        "serves it to the TV."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Tip: the TV must be on the same Wi-Fi as this phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCastDialog = false
                    CastCtl.stopDiscovery()
                }) { Text("Close") }
            }
        )
    }
}

/** Only ever called on API 26+. */
private fun buildPipParams(): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(true)
    }
    return builder.build()
}
