package com.zcc09.iptvplayer.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * An extra XMLTV guide, independent of any playlist.
 *
 * A playlist's own guide (Xtream `/xmltv.php`, or the per-playlist EPG URL)
 * describes that provider. This is the other case: a public feed that covers
 * channels from anywhere, added by the viewer and matched by channel id.
 */
@Serializable
data class EpgSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastFetchAt: Long = 0L,
    val lastOk: Boolean = false,
    val channelCount: Int = 0,
    val programCount: Int = 0,
    val lastError: String = ""
)

/** A guide we can name without the viewer having to know a URL. */
data class EpgPreset(val name: String, val url: String, val note: String)

/** Plain data in, plain data out, so the precedence rule can be unit tested. */
object EpgMerge {

    /**
     * The playlist's own guide always wins for a channel it covers; an extra
     * source only fills ids the provider left empty, so adding a public feed can
     * never overwrite better local data.
     */
    fun merge(
        own: Map<String, List<EpgProgram>>,
        extra: Map<String, List<EpgProgram>>
    ): Map<String, List<EpgProgram>> {
        if (extra.isEmpty()) return own
        val out = HashMap<String, List<EpgProgram>>(own.size + extra.size)
        out.putAll(own)
        for ((channelId, programs) in extra) {
            if (programs.isEmpty()) continue
            val existing = out[channelId]
            if (existing.isNullOrEmpty()) out[channelId] = programs
        }
        return out
    }
}

object EpgSources {

    /** A cached guide older than this is refreshed in the background. */
    const val STALE_MS = 6 * 60 * 60 * 1000L

    private const val PREFS = "epg_sources"
    private const val KEY = "sources"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _items = MutableStateFlow<List<EpgSource>>(emptyList())
    val items: StateFlow<List<EpgSource>> = _items.asStateFlow()

    private var prefs: SharedPreferences? = null
    private val caches = HashMap<String, EpgStore.EpgCache>()

    /**
     * Public XMLTV feeds, verified to serve XMLTV with real programme data.
     * The generic one covers every region the feed tracks; the rest are the
     * usual starting points.
     */
    val presets: List<EpgPreset> = listOf(
        EpgPreset(
            name = "Generic (worldwide)",
            url = "https://i.mjh.nz/all/epg.xml",
            note = "Every region this public feed covers"
        ),
        EpgPreset(
            name = "Pluto TV",
            url = "https://i.mjh.nz/PlutoTV/all.xml",
            note = "Pluto TV channels only"
        ),
        EpgPreset(
            name = "UAE & Arabic",
            url = "https://epgshare01.online/epgshare01/epg_ripper_AE1.xml.gz",
            note = "Gzipped feed — inflated automatically"
        )
    )

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY, null)
        _items.value = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<EpgSource>>(raw) }.getOrElse {
                Logx.w("EPG_SOURCES_LOAD_FAILED ${it.message}")
                emptyList()
            }
        }
        // Warm from disk only: no network at startup.
        for (s in _items.value) readCache(s.id)?.let { caches[s.id] = it }
    }

    fun source(id: String): EpgSource? = _items.value.firstOrNull { it.id == id }

    fun add(name: String, url: String): EpgSource {
        val clean = url.trim()
        val label = name.trim().ifBlank { hostOf(clean) }
        val source = EpgSource(id = UUID.randomUUID().toString().take(8), name = label, url = clean)
        _items.value = _items.value + source
        persist()
        return source
    }

    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        caches.remove(id)
        cacheFile(id)?.delete()
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _items.value = _items.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persist()
    }

    /**
     * Fetch a guide now and record what it contained, so the viewer sees
     * "38,438 programmes" instead of a URL and a hope. Also fills the cache the
     * guide screen reads.
     */
    suspend fun test(source: EpgSource): EpgSource {
        val started = System.currentTimeMillis()
        val result = runCatching {
            val xml = Http.getText(source.url, timeoutMs = 45_000, ua = Repo.userAgent)
            EpgParser.parse(xml)
        }
        val updated = result.fold(
            onSuccess = { programs ->
                val cache = EpgStore.EpgCache(
                    fetchedAt = started,
                    missing = programs.isEmpty(),
                    programs = programs
                )
                caches[source.id] = cache
                writeCache(source.id, cache)
                source.copy(
                    lastFetchAt = started,
                    lastOk = programs.isNotEmpty(),
                    channelCount = programs.size,
                    programCount = programs.values.sumOf { it.size },
                    lastError = if (programs.isEmpty()) "No <programme> entries in that file" else ""
                )
            },
            onFailure = { t ->
                source.copy(
                    lastFetchAt = started,
                    lastOk = false,
                    lastError = t.message?.take(120) ?: "Failed to fetch the guide"
                )
            }
        )
        _items.value = _items.value.map { if (it.id == updated.id) updated else it }
        persist()
        Logx.i(
            "EPG_SOURCE id=${updated.id} ok=${updated.lastOk} channels=${updated.channelCount} " +
                "programs=${updated.programCount} err=${updated.lastError.take(60)}"
        )
        return updated
    }

    /** Refresh enabled guides whose cache is missing or old. Never throws. */
    suspend fun refreshStale(now: Long = System.currentTimeMillis()) {
        for (s in _items.value.filter { it.enabled }) {
            val cache = caches[s.id] ?: readCache(s.id)?.also { caches[s.id] = it }
            val fresh = cache != null && now - cache.fetchedAt < STALE_MS && !cache.missing
            if (fresh) continue
            runCatching { test(s) }
        }
    }

    /** Programmes from every enabled guide, for merging into a playlist's cache. */
    fun cachedMerged(): Map<String, List<EpgProgram>> {
        if (_items.value.none { it.enabled }) return emptyMap()
        var merged: Map<String, List<EpgProgram>> = emptyMap()
        for (s in _items.value.filter { it.enabled }) {
            val cache = caches[s.id] ?: readCache(s.id)?.also { caches[s.id] = it } ?: continue
            if (cache.missing || cache.programs.isEmpty()) continue
            merged = EpgMerge.merge(merged, cache.programs)
        }
        return merged
    }

    fun channelIdHint(url: String): String = hostOf(url)

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: "Guide" }.getOrNull() ?: "Guide"

    private fun persist() {
        val body = runCatching { json.encodeToString(_items.value) }.getOrNull() ?: return
        prefs?.edit()?.putString(KEY, body)?.apply()
    }

    private fun cacheFile(id: String): File? =
        File(File(Repo.cacheDir, "epg_sources"), "$id.json")

    private fun readCache(id: String): EpgStore.EpgCache? {
        val f = cacheFile(id) ?: return null
        if (!f.exists()) return null
        return runCatching {
            json.decodeFromString(EpgStore.EpgCache.serializer(), f.readText())
        }.getOrNull()
    }

    private fun writeCache(id: String, cache: EpgStore.EpgCache) {
        val f = cacheFile(id) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(EpgStore.EpgCache.serializer(), cache))
        }.onFailure { Logx.w("EPG_SOURCE_CACHE_FAILED $id: ${it.message}") }
    }
}
