package com.zcc09.iptvplayer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A portable copy of what a viewer would hate to set up twice: the playlists,
 * the favourites and the "continue watching" history.
 *
 * Deliberately contains no device settings (interface mode, user agent, cast
 * mode) - those describe the device, not the account, and importing a phone
 * backup into a TV should not turn the TV into a touch UI.
 */
@Serializable
data class BackupDoc(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0L,
    val playlists: List<Playlist> = emptyList(),
    val favorites: List<String> = emptyList(),
    val history: List<WatchHistory.Entry> = emptyList()
) {
    companion object {
        const val FORMAT = "internet-tv-player-backup"
        const val VERSION = 1
    }
}

/** JSON in, JSON out. Pure so it can be unit tested without Android. */
object BackupCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(doc: BackupDoc): String = json.encodeToString(doc)

    /**
     * Null when the text is not one of our backups. Never throws: the caller is
     * a file picker, and a user picking the wrong file must get a message, not
     * a crash.
     */
    fun decode(text: String): BackupDoc? {
        if (!text.contains(BackupDoc.FORMAT)) return null
        val doc = runCatching { json.decodeFromString<BackupDoc>(text) }.getOrNull() ?: return null
        if (doc.format != BackupDoc.FORMAT) return null
        // A newer app wrote it: we do not know what the extra fields mean.
        if (doc.version > BackupDoc.VERSION) return null
        return doc
    }

    fun summary(doc: BackupDoc): String {
        val pl = doc.playlists.size
        return "$pl playlist${if (pl == 1) "" else "s"}, " +
            "${doc.favorites.size} favourite${if (doc.favorites.size == 1) "" else "s"}, " +
            "${doc.history.size} resume item${if (doc.history.size == 1) "" else "s"}"
    }
}
