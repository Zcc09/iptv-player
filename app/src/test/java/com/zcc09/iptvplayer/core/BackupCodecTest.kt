package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val playlist = Playlist(
        id = "p1",
        name = "Home panel",
        type = PlaylistType.XTREAM,
        url = "https://example.invalid",
        username = "user",
        password = "secret"
    )

    private val history = WatchHistory.Entry(
        key = "movie:p1:42",
        name = "A Film",
        playlistId = "p1",
        url = "https://example.invalid/42.mkv",
        positionMs = 60_000,
        durationMs = 600_000,
        updatedAt = 1_700_000_000_000
    )

    private fun doc() = BackupDoc(
        exportedAt = 1_700_000_000_000,
        playlists = listOf(playlist),
        favorites = listOf("ch-1", "ch-2"),
        history = listOf(history)
    )

    @Test
    fun roundTripsEverything() {
        val back = BackupCodec.decode(BackupCodec.encode(doc()))
        assertEquals(1, back?.playlists?.size)
        assertEquals(listOf("ch-1", "ch-2"), back?.favorites)
        assertEquals(1, back?.history?.size)
        // Resume points are the whole reason to carry history across devices.
        assertEquals(60_000L, back?.history?.first()?.positionMs)
        assertEquals("Home panel", back?.playlists?.first()?.name)
    }

    @Test
    fun encodesTheFormatMarker() {
        assertTrue(BackupCodec.encode(doc()).contains(BackupDoc.FORMAT))
    }

    @Test
    fun rejectsJsonThatIsNotABackup() {
        assertNull(BackupCodec.decode("{\"hello\":\"world\"}"))
    }

    @Test
    fun rejectsPlainTextAndEmptyInput() {
        assertNull(BackupCodec.decode("just a text file"))
        assertNull(BackupCodec.decode(""))
    }

    @Test
    fun rejectsANewerBackupVersion() {
        val newer = BackupCodec.encode(doc().copy(version = BackupDoc.VERSION + 1))
        assertNull(BackupCodec.decode(newer))
    }

    @Test
    fun summaryReadsAsWords() {
        assertEquals(
            "1 playlist, 2 favourites, 1 resume item",
            BackupCodec.summary(doc())
        )
        assertEquals("0 playlists, 0 favourites, 0 resume items", BackupCodec.summary(BackupDoc()))
    }
}
