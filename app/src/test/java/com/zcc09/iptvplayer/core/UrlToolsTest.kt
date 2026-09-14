package com.zcc09.iptvplayer.core

import org.junit.Assert.assertTrue
import org.junit.Test

class UrlToolsTest {

    @Test
    fun swapsExtensionOfXtreamLiveUrl() {
        val url = "https://tv.example/live/Family/123/1.ts"
        assertTrue(UrlTools.withExtension(url, "mp4") == "https://tv.example/live/Family/123/1.mp4")
    }

    @Test
    fun appendsExtensionWhenAbsent() {
        val url = "https://tv.example/proxy/ts/stream/ed319eed-da64"
        assertTrue(
            UrlTools.withExtension(url, "mp4") == "https://tv.example/proxy/ts/stream/ed319eed-da64.mp4"
        )
    }

    @Test
    fun keepsQueryString() {
        val url = "https://tv.example/live/u/p/1.ts?token=abc"
        assertTrue(UrlTools.withExtension(url, "mp4") == "https://tv.example/live/u/p/1.mp4?token=abc")
    }

    @Test
    fun doesNotTreatHostDotsAsExtension() {
        val url = "https://tv.example/stream"
        assertTrue(UrlTools.withExtension(url, "ts") == "https://tv.example/stream.ts")
    }

    @Test
    fun detectsHlsAndDash() {
        assertTrue(UrlTools.isHls("https://a.example/x/y.m3u8"))
        assertTrue(UrlTools.isHls("https://a.example/x/y.M3U8?t=1"))
        assertTrue(!UrlTools.isHls("https://a.example/x/y.ts"))
        assertTrue(UrlTools.isDash("https://a.example/x/y.mpd"))
    }

    @Test
    fun detectsRawTsIncludingExtensionlessProxyPaths() {
        assertTrue(UrlTools.looksLikeTs("https://a.example/live/u/p/1.ts"))
        assertTrue(UrlTools.looksLikeTs("https://tv.example/proxy/ts/stream/ed319eed"))
        assertTrue(!UrlTools.looksLikeTs("https://a.example/x/y.m3u8"))
        assertTrue(!UrlTools.looksLikeTs("https://a.example/x/y.mp4"))
    }

    @Test
    fun normalisesWhateverTheUserPastesAsAnXtreamBase() {
        assertTrue(
            XtreamUrls.normalize("https://tv.mojangle.net/output/m3u") == "https://tv.mojangle.net"
        )
        assertTrue(
            XtreamUrls.normalize("https://tv.mojangle.net/player_api.php?username=x") ==
                "https://tv.mojangle.net"
        )
        assertTrue(XtreamUrls.normalize("tv.example:8080") == "http://tv.example:8080")
        assertTrue(XtreamUrls.normalize("http://tv.example:8080/") == "http://tv.example:8080")
        assertTrue(XtreamUrls.normalize("  https://tv.example/get.php  ") == "https://tv.example")
    }

    @Test
    fun keepsSubPathsWhenNormalising() {
        assertTrue(XtreamUrls.normalize("https://tv.example/iptv/") == "https://tv.example/iptv")
    }
}
