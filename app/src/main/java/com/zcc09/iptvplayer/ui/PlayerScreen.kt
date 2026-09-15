package com.zcc09.iptvplayer.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var showTvOsd by remember { mutableStateOf(false) }
    var showZapBanner by remember { mutableStateOf(false) }
    var showChannelDrawer by remember { mutableStateOf(false) }
    var videoSizeText by remember { mutableStateOf<String?>(null) }
    val showNavHints by Repo.showNavHints.collectAsState()
    val focusRequester = remember { FocusRequester() }

    val exo = remember(channel.id) { Playback.createPlayer(context, Repo.userAgent) }
    val siblings = remember(channel.playlistId) { Repo.channelsOf(channel.playlistId) }
    val pipSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(showZapBanner) {
        if (showZapBanner) {
            delay(3500)
            showZapBanner = false
        }
    }

    BackHandler(enabled = showChannelDrawer || showTvOsd) {
        if (showChannelDrawer) showChannelDrawer = false
        else if (showTvOsd) showTvOsd = false
    }

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
                videoSizeText = "${videoSize.width}x${videoSize.height}"
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
        showZapBanner = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_CHANNEL_UP,
                        KeyEvent.KEYCODE_PAGE_UP -> {
                            move(1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_CHANNEL_DOWN,
                        KeyEvent.KEYCODE_PAGE_DOWN -> {
                            move(-1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_BUTTON_A -> {
                            showTvOsd = !showTvOsd
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_BUTTON_X,
                        KeyEvent.KEYCODE_GUIDE,
                        KeyEvent.KEYCODE_MENU -> {
                            showChannelDrawer = !showChannelDrawer
                            true
                        }
                        KeyEvent.KEYCODE_BUTTON_Y -> {
                            Repo.toggleFavorite(channel.id)
                            val action = if (Repo.isFavorite(channel.id)) "Added to" else "Removed from"
                            Repo.postStatus("$action Favourites: ${channel.name}")
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (exo.isPlaying) exo.pause() else exo.play()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            exo.play()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            exo.pause()
                            true
                        }
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_BUTTON_B -> {
                            if (showChannelDrawer) {
                                showChannelDrawer = false
                                true
                            } else if (showTvOsd) {
                                showTvOsd = false
                                true
                            } else {
                                Nav.pop()
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
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

        // --------------------------------------------------------- TV Zap Channel Banner
        AnimatedVisibility(
            visible = showZapBanner,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
        ) {
            Surface(
                color = Color(0xFF0F172A).copy(alpha = 0.92f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                tonalElevation = 8.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    RemoteImage(
                        url = channel.logo,
                        contentDescription = channel.name,
                        size = 48.dp,
                        modifier = Modifier.background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (channel.group.isNotBlank()) {
                                Text(channel.group, fontSize = 12.sp, color = Color(0xFF94A3B8))
                            }
                            TvBadge(text = "LIVE", color = Color(0xFFEF4444))
                            videoSizeText?.let { TvBadge(text = it, color = Color(0xFF10B981)) }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------- TV Quick Channel Drawer (Mini-Guide)
        AnimatedVisibility(
            visible = showChannelDrawer,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Surface(
                color = Color(0xFF0A0F1D).copy(alpha = 0.95f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Channels (${siblings.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showChannelDrawer = false }) {
                            Icon(Icons.Default.Clear, contentDescription = "Close", tint = Color.Gray)
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(siblings, key = { it.id }) { ch ->
                            val isCurrent = ch.id == channel.id
                            TvFocusableCard(
                                onClick = {
                                    Nav.replaceTop(Screen.Player(ch, null))
                                    showChannelDrawer = false
                                },
                                focusedBorderColor = Color(0xFF38BDF8),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(if (isCurrent) Color(0xFF1E293B) else Color.Transparent)
                                        .padding(horizontal = 10.dp)
                                ) {
                                    RemoteImage(url = ch.logo, contentDescription = ch.name, size = 34.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = ch.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                        if (ch.group.isNotBlank()) {
                                            Text(ch.group, fontSize = 10.sp, color = Color(0xFF94A3B8), maxLines = 1)
                                        }
                                    }
                                    if (isCurrent) {
                                        TvBadge(text = "PLAYING", color = Color(0xFF38BDF8))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------- TV OSD Overlay Bar
        AnimatedVisibility(
            visible = showTvOsd,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TvFocusableCard(
                            onClick = { move(-1) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(4.dp))
                                Text("Prev Ch", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        TvFocusableCard(
                            onClick = {
                                if (exo.isPlaying) exo.pause() else exo.play()
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(4.dp))
                                Text(if (exo.isPlaying) "Pause" else "Play", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        TvFocusableCard(
                            onClick = { move(1) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Next Ch", color = Color.White, fontSize = 12.sp)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.White)
                            }
                        }

                        TvFocusableCard(
                            onClick = { showChannelDrawer = !showChannelDrawer },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("📺 Mini-Guide", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        TvFocusableCard(
                            onClick = { aspectIndex = (aspectIndex + 1) % ASPECTS.size },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("🔲 ${ASPECTS[aspectIndex].first}", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        TvFocusableCard(
                            onClick = {
                                Repo.toggleFavorite(channel.id)
                                val action = if (Repo.isFavorite(channel.id)) "Added to" else "Removed from"
                                Repo.postStatus("$action Favourites: ${channel.name}")
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(if (Repo.isFavorite(channel.id)) "★ Favourited" else "☆ Favourite", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        if (CastCtl.available) {
                            TvFocusableCard(
                                onClick = { showCastDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(42.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    CastGlyph(size = 16.dp, color = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (casting) "Casting" else "Cast", color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    if (showNavHints) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            RemoteButtonHint(buttonLabel = "▲/▼", actionName = "Zap Channel")
                            Spacer(Modifier.width(10.dp))
                            RemoteButtonHint(buttonLabel = "◄/►", actionName = "Mini Guide")
                            Spacer(Modifier.width(10.dp))
                            RemoteButtonHint(buttonLabel = "[A]/OK", actionName = "Toggle OSD")
                            Spacer(Modifier.width(10.dp))
                            RemoteButtonHint(buttonLabel = "[B]/Back", actionName = "Close/Exit")
                        }
                    }
                }
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
