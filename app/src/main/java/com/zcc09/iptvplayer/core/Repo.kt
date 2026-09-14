package com.zcc09.iptvplayer.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.UUID

/** A pending "open the player" request coming from the UI or from the debug hooks. */
data class PlayRequest(val channel: Channel, val urlOverride: String? = null)

/**
 * Single source of truth for playlists, their cached channels and user settings.
 * Persisted as JSON: playlists in SharedPreferences, channel caches in files
 * (they can be tens of thousands of entries).
 */
object Repo {

    private const val PREFS = "iptv_player"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_UA = "user_agent"
    private const val KEY_CAST_MODE = "cast_mode"
    private const val KEY_FAVORITES = "favorites"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val playlistSerializer = ListSerializer(Playlist.serializer())
    private val channelSerializer = ListSerializer(Channel.serializer())

    private var app: Context? = null
    private var prefs: SharedPreferences? = null

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _channels = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())
    val channels: StateFlow<Map<String, List<Channel>>> = _channels.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _castMode = MutableStateFlow(CastMode.AUTO)
    val castMode: StateFlow<CastMode> = _castMode.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _playRequest = MutableStateFlow<PlayRequest?>(null)
    val playRequest: StateFlow<PlayRequest?> = _playRequest.asStateFlow()

    @Volatile
    var userAgent: String = Http.DEFAULT_UA
        private set

    val isReady: Boolean get() = prefs != null

    fun init(context: Context) {
        if (prefs != null) return
        val ctx = context.applicationContext
        app = ctx
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        userAgent = p.getString(KEY_UA, null)?.takeIf { it.isNotBlank() } ?: Http.DEFAULT_UA
        _castMode.value = runCatching {
            CastMode.valueOf(p.getString(KEY_CAST_MODE, CastMode.AUTO.name) ?: CastMode.AUTO.name)
        }.getOrDefault(CastMode.AUTO)
        _favorites.value = p.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()
        loadPlaylists()
    }

    // ---------------------------------------------------------------- playlists

    private fun loadPlaylists() {
        val raw = prefs?.getString(KEY_PLAYLISTS, null)
        val list = if (raw.isNullOrBlank()) emptyList() else runCatching {
            json.decodeFromString(playlistSerializer, raw)
        }.getOrElse {
            Logx.e("Failed to read saved playlists", it)
            emptyList()
        }
        _playlists.value = list
        val map = HashMap<String, List<Channel>>()
        for (pl in list) map[pl.id] = readChannelCache(pl.id)
        _channels.value = map
    }

    private fun persistPlaylists() {
        prefs?.edit()?.putString(KEY_PLAYLISTS, json.encodeToString(playlistSerializer, _playlists.value))?.apply()
    }

    private fun channelFile(playlistId: String): File =
        File(app?.filesDir ?: File("."), "channels_${playlistId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    private fun readChannelCache(playlistId: String): List<Channel> {
        val f = channelFile(playlistId)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(channelSerializer, f.readText())
        }.getOrElse {
            Logx.w("channel cache unreadable for $playlistId: ${it.message}")
            emptyList()
        }
    }

    private fun writeChannelCache(playlistId: String, list: List<Channel>) {
        runCatching { channelFile(playlistId).writeText(json.encodeToString(channelSerializer, list)) }
            .onFailure { Logx.w("could not persist channels for $playlistId: ${it.message}") }
    }

    fun channelsOf(playlistId: String): List<Channel> = _channels.value[playlistId].orEmpty()

    fun playlist(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    fun allChannels(): List<Channel> = _channels.value.values.flatten()

    fun channelById(id: String): Channel? =
        _channels.value.values.asSequence().flatten().firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun add(playlist: Playlist) {
        _playlists.value = _playlists.value + playlist
        persistPlaylists()
        Scheduler.schedule(app ?: return, playlist)
    }

    fun update(playlist: Playlist) {
        _playlists.value = _playlists.value.map { if (it.id == playlist.id) playlist else it }
        persistPlaylists()
        Scheduler.schedule(app ?: return, playlist)
    }

    fun delete(playlistId: String) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        _channels.value = _channels.value - playlistId
        persistPlaylists()
        channelFile(playlistId).delete()
        Scheduler.cancel(app ?: return, playlistId)
    }

    // ---------------------------------------------------------------- settings

    fun setUserAgent(value: String) {
        val v = value.trim().ifBlank { Http.DEFAULT_UA }
        userAgent = v
        prefs?.edit()?.putString(KEY_UA, v)?.apply()
    }

    fun setCastMode(mode: CastMode) {
        _castMode.value = mode
        prefs?.edit()?.putString(KEY_CAST_MODE, mode.name)?.apply()
    }

    fun isFavorite(channelId: String): Boolean = _favorites.value.contains(channelId)

    fun toggleFavorite(channelId: String) {
        val next = _favorites.value.toMutableSet()
        if (!next.add(channelId)) next.remove(channelId)
        _favorites.value = next
        prefs?.edit()?.putStringSet(KEY_FAVORITES, next)?.apply()
    }

    fun postStatus(message: String) {
        _status.value = message
    }

    fun clearStatus() {
        _status.value = ""
    }

    // ---------------------------------------------------------------- playback requests

    fun requestPlay(channel: Channel, urlOverride: String? = null) {
        _playRequest.value = PlayRequest(channel, urlOverride)
    }

    fun consumePlayRequest() {
        _playRequest.value = null
    }

    // ---------------------------------------------------------------- refreshing

    suspend fun fetch(p: Playlist): RefreshResult = withContext(Dispatchers.IO) {
        val ua = userAgent
        try {
            when (p.type) {
                PlaylistType.M3U -> {
                    val text = Http.getText(p.url, timeoutMs = 30000, ua = ua)
                    if (!text.contains("#EXTINF", ignoreCase = true)) {
                        return@withContext RefreshResult(
                            channels = emptyList(),
                            error = "Not an M3U playlist (no #EXTINF entries found)"
                        )
                    }
                    val parsed = M3uParser.parse(text, p.id)
                    if (parsed.channels.isEmpty()) {
                        return@withContext RefreshResult(emptyList(), error = "Playlist contained no channels")
                    }
                    RefreshResult(parsed.channels, parsed.epgUrl, parsed.userAgent)
                }

                PlaylistType.XTREAM -> {
                    val api = XtreamApi(p.url, p.username, p.password, ua)
                    val auth = api.auth()
                    if (!auth.ok) {
                        return@withContext RefreshResult(
                            emptyList(),
                            error = "Xtream login rejected (status=${auth.status})"
                        )
                    }
                    val groups = runCatching { api.liveCategories().associate { it.id to it.name } }
                        .getOrDefault(emptyMap())
                    val streams = api.liveStreams()
                    val ext = api.preferredExtension(auth.formats)
                    val list = streams.map { s ->
                        Channel(
                            id = "${p.id}#${s.id}",
                            playlistId = p.id,
                            name = s.name,
                            group = groups[s.categoryId] ?: "",
                            logo = s.icon,
                            url = api.streamUrl(s, ext),
                            epgId = s.epgId,
                            number = s.number,
                            streamId = s.id
                        )
                    }
                    if (list.isEmpty()) {
                        return@withContext RefreshResult(emptyList(), error = "Xtream account returned no live streams")
                    }
                    Logx.i("Xtream login ok: user=${p.username} status=${auth.status} formats=${auth.formats} streams=${list.size}")
                    RefreshResult(list)
                }
            }
        } catch (t: Throwable) {
            Logx.e("Refresh failed for ${p.name}", t)
            RefreshResult(emptyList(), error = t.message ?: t.javaClass.simpleName)
        }
    }

    /** Refresh one playlist and persist the outcome. Returns the refreshed channels. */
    suspend fun refresh(playlistId: String): RefreshResult {
        val p = playlist(playlistId) ?: return RefreshResult(emptyList(), error = "Unknown playlist")
        _busy.value = _busy.value + playlistId
        return try {
            val result = fetch(p)
            if (result.ok) {
                _channels.value = _channels.value + (playlistId to result.channels)
                writeChannelCache(playlistId, result.channels)
            }
            val updated = p.copy(
                lastRefresh = System.currentTimeMillis(),
                lastRefreshOk = result.ok,
                lastError = result.error ?: "",
                channelCount = if (result.ok) result.channels.size else p.channelCount
            )
            _playlists.value = _playlists.value.map { if (it.id == updated.id) updated else it }
            persistPlaylists()
            if (result.ok) {
                Logx.i("REFRESH_OK playlist=${p.name} channels=${result.channels.size}")
            } else {
                Logx.w("REFRESH_FAIL playlist=${p.name} error=${result.error}")
            }
            result
        } finally {
            _busy.value = _busy.value - playlistId
        }
    }

    /** Refresh every playlist (used by the auto-refresh worker and the refresh-all button). */
    suspend fun refreshAll(): Int {
        var total = 0
        for (p in _playlists.value) {
            val r = refresh(p.id)
            if (r.ok) total += r.channels.size
        }
        Logx.i("REFRESH_ALL_DONE channels=$total playlists=${_playlists.value.size}")
        return total
    }

    fun refreshAsync(playlistId: String) {
        scope.launch { refresh(playlistId) }
    }

    fun refreshAllAsync() {
        scope.launch { refreshAll() }
    }

    /** Auto-refresh playlists whose interval has elapsed. Called when the app starts. */
    fun refreshStaleAsync() {
        scope.launch {
            val now = System.currentTimeMillis()
            for (p in _playlists.value) {
                if (!p.autoRefresh) continue
                val ageMinutes = (now - p.lastRefresh) / 60000L
                if (p.lastRefresh == 0L || ageMinutes >= p.refreshIntervalMinutes) {
                    Logx.i("AUTO_REFRESH_DUE playlist=${p.name} ageMin=$ageMinutes interval=${p.refreshIntervalMinutes}")
                    refresh(p.id)
                }
            }
        }
    }
}
