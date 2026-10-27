package com.zcc09.iptvplayer.core

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * EPG cache for live Xtream accounts: an in-memory stream-id -> programs map
 * plus a per-playlist JSON file in the app cache directory. The guide screen
 * refreshes the cache when it opens if it is older than 10 minutes. Servers
 * that do not serve EPG (404 / empty / error) degrade to a `missing`
 * snapshot and the UI shows a friendly no-guide state instead of failing.
 */
object EpgStore {

    /** One fetched EPG snapshot for a playlist. */
    @Serializable
    data class EpgCache(
        val fetchedAt: Long,
        val missing: Boolean,
        val programs: Map<String, List<EpgProgram>>
    )

    /** True when the snapshot carries no usable EPG at all. */
    val EpgCache.empty: Boolean
        get() = missing || programs.isEmpty()

    /** Caches older than this are refetched the next time the guide opens. */
    const val STALE_AFTER_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val store = HashMap<String, EpgCache>()
    private val flows = HashMap<String, MutableStateFlow<EpgCache?>>()
    private val refreshing = HashSet<String>()
    private var appContext: Context? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

    /** Load the per-playlist JSON caches from disk into memory. Call once at startup. */
    fun init(context: Context) {
        appContext = context
        val dir = File(Repo.cacheDir, "epg")
        if (!dir.exists()) return
        dir.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { f ->
            runCatching {
                val cache = json.decodeFromString(EpgCache.serializer(), f.readText())
                scope.launch { publish(f.nameWithoutExtension, cache) }
            }.onFailure { Logx.w("EPG_LOAD_FAILED ${f.name}: ${it.message}") }
        }
    }

    /** Observable cache state for a playlist (flow created on first read). */
    fun flow(playlistId: String): StateFlow<EpgCache?> =
        synchronized(lock) {
            flows.getOrPut(playlistId) { MutableStateFlow(store[playlistId]) }
        }

    fun isStale(playlistId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cache = synchronized(lock) { store[playlistId] } ?: return true
        return nowMs - cache.fetchedAt > STALE_AFTER_MS
    }

    /** The program airing for [streamId] at [nowSec] (unix seconds), if any. */
    fun nowProgram(playlistId: String, streamId: Int, nowSec: Long): EpgProgram? =
        cache(playlistId)?.programs?.get(streamId.toString())?.let { EpgParser.nowAt(it, nowSec) }

    /** The next program for [streamId] starting after [nowSec], if any. */
    fun nextProgram(playlistId: String, streamId: Int, nowSec: Long): EpgProgram? =
        cache(playlistId)?.programs?.get(streamId.toString())?.let { EpgParser.nextAt(it, nowSec) }

    /**
     * Fetch today 00:00 through tomorrow 23:59 (device local time) from the
     * account's panel and replace the cache. Any failure (404, empty, network)
     * is recorded as a missing snapshot so the UI degrades to the friendly
     * no-guide state.
     */
    suspend fun refresh(playlist: Playlist) {
        if (playlist.type != PlaylistType.XTREAM) return
        if (!isStale(playlist.id)) return
        synchronized(lock) { if (!refreshing.add(playlist.id)) return }
        try {
            val cache = withContext(Dispatchers.IO) {
                val api = XtreamApi(
                    playlist.url,
                    playlist.username,
                    playlist.password,
                    Repo.userAgent
                )
                val today = LocalDate.now()
                val xml = runCatching {
                    api.fullEpg(today.format(dateFmt), today.plusDays(1).format(dateFmt))
                }.getOrNull()
                val programs = xml?.let { runCatching { EpgParser.parse(it) }.getOrNull() } ?: emptyMap()
                EpgCache(
                    fetchedAt = System.currentTimeMillis(),
                    missing = programs.isEmpty(),
                    programs = programs
                )
            }
            if (cache.missing) Logx.i("E2E EPG_MISSING playlist=${playlist.id}")
            Logx.i("EPG_FETCHED playlist=${playlist.id} streams=${cache.programs.size} missing=${cache.missing}")
            publish(playlist.id, cache)
        } catch (t: Throwable) {
            Logx.w("EPG_REFRESH_FAILED ${playlist.name}: ${t.message}")
            publish(
                playlist.id,
                EpgCache(
                    fetchedAt = System.currentTimeMillis(),
                    missing = true,
                    programs = emptyMap()
                )
            )
        } finally {
            synchronized(lock) { refreshing.remove(playlist.id) }
        }
    }

    private fun cache(playlistId: String): EpgCache? =
        synchronized(lock) { store[playlistId] }

    private suspend fun publish(playlistId: String, cache: EpgCache) {
        synchronized(lock) {
            store[playlistId] = cache
            flows.getOrPut(playlistId) { MutableStateFlow(store[playlistId]) }.value = cache
        }
        withContext(Dispatchers.IO) {
            val file = cacheFile(playlistId) ?: return@withContext
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(cache))
            }.onFailure { Logx.w("EPG_SAVE_FAILED $playlistId: ${it.message}") }
        }
    }

    private fun cacheFile(playlistId: String): File? {
        val context = appContext ?: return null
        return File(File(Repo.cacheDir, "epg"), "$playlistId.json")
    }
}
