package com.zcc09.iptvplayer.cast

import android.content.Context
import android.net.Uri
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.zcc09.iptvplayer.core.Logx
import com.zcc09.iptvplayer.player.Playback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Chromecast support. Every entry point is defensive: Chromecast needs Google
 * Play Services, and the app must keep working (local playback, no cast button)
 * on devices without it.
 */
object CastCtl {

    data class RouteItem(
        val id: String,
        val name: String,
        val description: String,
        val info: MediaRouter.RouteInfo
    )

    @Volatile
    var available: Boolean = false
        private set

    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null

    private val _casting = MutableStateFlow(false)
    val casting: StateFlow<Boolean> = _casting.asStateFlow()

    private val _routes = MutableStateFlow<List<RouteItem>>(emptyList())
    val routes: StateFlow<List<RouteItem>> = _routes.asStateFlow()

    private val _device = MutableStateFlow("")
    /** Name of the Cast device the user picked (blanks when disconnected). */
    val device: StateFlow<String> = _device.asStateFlow()

    @Volatile
    private var scanning = false

    private var router: MediaRouter? = null
    private var callback: MediaRouter.Callback? = null

    fun init(context: Context) {
        if (castContext != null) return
        try {
            val ctx = CastContext.getSharedInstance(context.applicationContext)
            castContext = ctx
            val player = CastPlayer.Builder(context.applicationContext).build()
            player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    _casting.value = true
                    Logx.i("CAST_SESSION_AVAILABLE")
                }

                override fun onCastSessionUnavailable() {
                    _casting.value = false
                    Logx.i("CAST_SESSION_UNAVAILABLE")
                }
            })
            castPlayer = player
            available = true
            Logx.i("CAST_INIT_OK")
        } catch (t: Throwable) {
            available = false
            Logx.w("CAST_INIT_FAILED ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    val isScanning: Boolean get() = scanning

    fun startDiscovery(context: Context) {
        val cc = castContext
        if (cc == null) {
            Logx.w("CAST_DISCOVERY_SKIPPED (cast unavailable)")
            return
        }
        try {
            val appCtx = context.applicationContext
            val r = MediaRouter.getInstance(appCtx)
            router = r
            val selector = cc.mergedSelector
            if (selector == null) {
                Logx.w("CAST_DISCOVERY_SKIPPED (no cast app selector available)")
                return
            }
            if (callback == null) {
                callback = object : MediaRouter.Callback() {
                    override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                        refreshRoutes()
                    }

                    override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                        refreshRoutes()
                    }

                    override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                        refreshRoutes()
                    }
                }
            }
            callback?.let {
                r.addCallback(selector, it, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            }
            scanning = true
            refreshRoutes()
            Logx.i("CAST_DISCOVERY_STARTED routes=${_routes.value.size}")
        } catch (t: Throwable) {
            Logx.w("CAST_DISCOVERY_FAILED ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun stopDiscovery() {
        val r = router ?: return
        val cb = callback ?: return
        runCatching { r.removeCallback(cb) }
        scanning = false
    }

    private fun refreshRoutes() {
        val r = router ?: return
        val selector = castContext?.mergedSelector ?: return
        val out = ArrayList<RouteItem>()
        try {
            val default = r.defaultRoute
            for (route in r.routes) {
                if (route === default) continue
                if (!route.isEnabled) continue
                if (!route.matchesSelector(selector)) continue
                out.add(
                    RouteItem(
                        id = route.id ?: route.name.toString(),
                        name = route.name?.toString() ?: "Cast device",
                        description = route.description?.toString() ?: "",
                        info = route
                    )
                )
            }
        } catch (t: Throwable) {
            Logx.w("CAST_ROUTE_SCAN_FAILED ${t.javaClass.simpleName}: ${t.message}")
        }
        _routes.value = out
    }

    fun connect(route: RouteItem) {
        try {
            _device.value = route.name
            router?.selectRoute(route.info)
            Logx.i("CAST_CONNECT_REQUESTED device=${route.name}")
        } catch (t: Throwable) {
            Logx.w("CAST_CONNECT_FAILED ${t.message}")
        }
    }

    fun disconnect() {
        try {
            castContext?.sessionManager?.endCurrentSession(true)
            _device.value = ""
            Logx.i("CAST_DISCONNECTED")
        } catch (t: Throwable) {
            Logx.w("CAST_DISCONNECT_FAILED ${t.message}")
        }
    }

    /** Push a stream to the connected TV. Returns false when nothing was sent. */
    fun load(url: String, title: String, logo: String): Boolean {
        val player = castPlayer ?: return false
        return try {
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .apply { if (logo.isNotBlank()) setArtworkUri(Uri.parse(logo)) }
                .build()
            val item = MediaItem.Builder()
                .setUri(url)
                .setMimeType(Playback.mimeFor(url))
                .setMediaMetadata(metadata)
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
            Logx.i("CAST_LOAD url=$url")
            true
        } catch (t: Throwable) {
            Logx.e("CAST_LOAD_FAILED", t)
            false
        }
    }

    fun pause() {
        runCatching { castPlayer?.playWhenReady = false }
    }

    fun localPlayer(): CastPlayer? = castPlayer
}
