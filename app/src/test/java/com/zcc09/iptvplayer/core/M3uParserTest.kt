package com.zcc09.iptvplayer.core

import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = listOf(
        "#EXTM3U x-tvg-url=\"https://example.com/epg.xml\" url-tvg=\"https://example.com/epg2.xml\"",
        "#EXTVLCOPT:http-user-agent=VLC/3.0.18",
        "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"Al Emarat TV\" tvg-logo=\"https://example.com/logo1.png\" " +
            "tvg-chno=\"1\" group-title=\"AR| EMIRATES 4K\",AR: Al Emarat TV 4K",
        "https://example.com/proxy/ts/stream/aaa-bbb",
        "#KODIPROP:inputstream.adaptive.license_type=clearkey",
        "#EXTGRP:News",
        "#EXTINF:-1 tvg-id=\"2\",Channel, with comma",
        "https://example.com/live/Family/123/2.ts",
        "",
        "#EXTINF:-1,Plain entry",
        "http://example.com/plain",
        "#EXTINF:-1 tvg-chno=\"oops\",Bad number",
        "https://example.com/badnumber.ts"
    ).joinToString("\n")

    @Test
    fun parsesChannelsAttributesAndGroups() {
        val parsed = M3uParser.parse(sample, "pl1")
        val channels = parsed.channels

        assertTrue("expected 4 channels, got ${channels.size}: ${channels.map { it.name }}", channels.size == 4)

        val first = channels[0]
        assertTrue("name=${first.name}", first.name == "AR: Al Emarat TV 4K")
        assertTrue("group=${first.group}", first.group == "AR| EMIRATES 4K")
        assertTrue("logo=${first.logo}", first.logo == "https://example.com/logo1.png")
        assertTrue("epgId=${first.epgId}", first.epgId == "1")
        assertTrue("number=${first.number}", first.number == 1)
        assertTrue("url=${first.url}", first.url == "https://example.com/proxy/ts/stream/aaa-bbb")
        assertTrue("playlistId=${first.playlistId}", first.playlistId == "pl1")

        // #EXTGRP applies to the following entry and the display name keeps its comma
        val second = channels[1]
        assertTrue("name=${second.name}", second.name == "Channel, with comma")
        assertTrue("group=${second.group}", second.group == "News")

        // no attributes at all
        assertTrue("name=${channels[2].name}", channels[2].name == "Plain entry")
        assertTrue("url=${channels[2].url}", channels[2].url == "http://example.com/plain")

        // non-numeric tvg-chno must not crash the parser
        assertTrue("number=${channels[3].number}", channels[3].number == 0)

        val ids = channels.map { it.id }.toSet()
        assertTrue("ids must be unique: $ids", ids.size == channels.size)
    }

    @Test
    fun readsEpgUrlAndUserAgentFromHeader() {
        val parsed = M3uParser.parse(sample, "pl1")
        assertTrue("epgUrl=${parsed.epgUrl}", parsed.epgUrl == "https://example.com/epg2.xml")
        assertTrue("ua=${parsed.userAgent}", parsed.userAgent == "VLC/3.0.18")
    }

    @Test
    fun toleratesCrlfBomAndBlankLines() {
        val text = "\uFEFF#EXTM3U\r\n\r\n#EXTINF:-1,One\r\nhttp://a.example/1.ts\r\n\r\n" +
            "#EXTINF:-1,Two\r\nhttp://a.example/2.ts\r\n"
        val channels = M3uParser.parse(text, "crlf").channels
        assertTrue("count=${channels.size}", channels.size == 2)
        assertTrue("name0=${channels[0].name}", channels[0].name == "One")
        assertTrue("name1=${channels[1].name}", channels[1].name == "Two")
        assertTrue("url1=${channels[1].url}", channels[1].url == "http://a.example/2.ts")
    }

    @Test
    fun fallsBackToUrlWhenNameMissing() {
        val text = "#EXTM3U\n#EXTINF:-1,\nhttps://a.example/path/stream.ts\n"
        val channels = M3uParser.parse(text, "x").channels
        assertTrue("count=${channels.size}", channels.size == 1)
        assertTrue("name=${channels[0].name}", channels[0].name == "stream.ts")
    }

    @Test
    fun ignoresEntriesWithoutLocation() {
        val text = "#EXTM3U\n#EXTINF:-1,Orphan\n#EXTINF:-1,Real\nhttp://a.example/ok.ts\n"
        val channels = M3uParser.parse(text, "x").channels
        assertTrue("count=${channels.size}", channels.size == 1)
        assertTrue("name=${channels[0].name}", channels[0].name == "Real")
    }
}
