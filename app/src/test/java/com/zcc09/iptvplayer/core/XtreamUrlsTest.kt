package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VOD playback URLs are built from the panel base plus the account credentials,
 * so they are worth pinning: a wrong path shape means every film and episode
 * fails to play while everything else still looks fine.
 */
class XtreamUrlsTest {

    @Test
    fun `movie url uses the movie path with the stream id and extension`() {
        assertEquals(
            "https://tv.example.com/movie/Family/12345/98765.mkv",
            XtreamUrls.movie("https://tv.example.com", "Family", "12345", 98765, "mkv")
        )
    }

    @Test
    fun `episode url uses the series path, not the movie one`() {
        assertEquals(
            "https://tv.example.com/series/Family/12345/4321.mkv",
            XtreamUrls.episode("https://tv.example.com", "Family", "12345", 4321, "mkv")
        )
    }

    @Test
    fun `blank extensions fall back to the container each path expects`() {
        assertTrue(XtreamUrls.movie("https://h", "u", "p", 7, "").endsWith("/7.mp4"))
        assertTrue(XtreamUrls.episode("https://h", "u", "p", 7, "").endsWith("/7.mkv"))
    }

    @Test
    fun `credentials are percent encoded`() {
        val url = XtreamUrls.movie("https://h", "a b", "p@ss/word", 5, "mkv")
        assertTrue(url, url.contains("/a%20b/"))
        assertTrue(url, url.contains("p%40ss%2Fword"))
    }

    @Test
    fun `an extension pasted with a leading dot does not double up`() {
        // Panels are inconsistent about this, and "123..mkv" 404s.
        assertTrue(XtreamUrls.movie("https://h", "u", "p", 5, ".mkv").endsWith("/5.mkv"))
        assertTrue(XtreamUrls.episode("https://h", "u", "p", 5, " .mkv ").endsWith("/5.mkv"))
    }

    @Test
    fun `live, movie and series paths stay distinct`() {
        val movie = XtreamUrls.movie("https://h", "u", "p", 5, "mkv")
        val episode = XtreamUrls.episode("https://h", "u", "p", 5, "mkv")
        assertTrue(movie.contains("/movie/"))
        assertTrue(episode.contains("/series/"))
        assertTrue(movie != episode)
    }
}
