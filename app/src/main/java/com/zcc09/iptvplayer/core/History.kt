package com.zcc09.iptvplayer.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The resume rule, kept Android-free (and therefore unit tested): never rewind
 * for a few seconds of viewing, never resume something effectively finished.
 */
object ResumeRule {

    const val MIN_RESUME_MS = 30_000L
    private const val FINISHED_NUMERATOR = 95L
    private const val FINISHED_DENOMINATOR = 100L

    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_RESUME_MS) return false
        if (durationMs > 0L && positionMs >= (durationMs * FINISHED_NUMERATOR) / FINISHED_DENOMINATOR) return false
        return true
    }
}

/**
 * Watch history for on-demand playback: where each film/episode was left, so the
 * player can resume and the Movies tab can offer "Continue watching".
 *
 * Only on-demand entries are recorded - a live channel has no position worth
 * keeping. The resume rule lives in [ResumeRule].
 */
object WatchHistory {

    @Serializable
    data class Entry(
        /** Channel id used by the player: "movie:<playlist>:<item>" or "episode:<playlist>:<ep>". */
        val key: String,
        val name: String,
        val playlistId: String,
        val url: String,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long
    ) {
        val progress: Float
            get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /** Keep the list short: it is a convenience row, not an archive. */
    const val MAX_ENTRIES = 40

    private const val FILE_NAME = "history.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val _items = MutableStateFlow<List<Entry>>(emptyList())
    val items: StateFlow<List<Entry>> = _items.asStateFlow()
    private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.applicationContext.filesDir, "")
        dir = d
        val f = File(d, FILE_NAME)
        if (!f.exists()) return
        runCatching {
            _items.value = json.decodeFromString(serializer(), f.readText())
                .sortedByDescending { it.updatedAt }
                .take(MAX_ENTRIES)
        }.onFailure { Logx.w("HISTORY_LOAD_FAILED ${it.message}") }
    }

    /** True for ids that belong to on-demand playback. */
    fun isOnDemand(channelId: String): Boolean =
        channelId.startsWith("movie:") || channelId.startsWith("episode:")

    /**
     * Whether a saved position is worth seeking to: long enough that the viewer
     * actually started, and not so far that they finished the title.
     */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean =
        ResumeRule.shouldResume(positionMs, durationMs)

    fun position(key: String): Long = _items.value.firstOrNull { it.key == key }?.positionMs ?: 0L

    /** Upsert progress. Called periodically during playback. */
    fun record(
        key: String,
        name: String,
        playlistId: String,
        url: String,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!isOnDemand(key)) return
        if (positionMs <= 0L) return
        val entry = Entry(key, name, playlistId, url, positionMs, durationMs, nowMs)
        val next = (_items.value.filterNot { it.key == key } + entry)
            .sortedByDescending { it.updatedAt }
            .take(MAX_ENTRIES)
        _items.value = next
        persist(next)
    }

    fun remove(key: String) {
        val next = _items.value.filterNot { it.key == key }
        _items.value = next
        persist(next)
    }

    fun clear() {
        _items.value = emptyList()
        persist(emptyList())
    }

    private fun persist(list: List<Entry>) {
        val d = dir ?: return
        val body = runCatching { json.encodeToString(serializer(), list) }.getOrNull() ?: return
        scope.launch {
            runCatching { File(d, FILE_NAME).writeText(body) }
                .onFailure { Logx.w("HISTORY_SAVE_FAILED ${it.message}") }
        }
    }

    private fun serializer() = kotlinx.serialization.builtins.ListSerializer(Entry.serializer())
}
