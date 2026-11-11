package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgStreamTest {

    private val header = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
        <channel id="bbc.uk"><display-name>BBC One</display-name></channel>
    """.trimIndent()

    /** A public-guide-shaped document: one programme per line. */
    private fun feed(vararg programmes: String) =
        (header + "\n" + programmes.joinToString("\n") + "\n</tv>\n").byteInputStream()

    private fun programme(channel: String, start: String, stop: String, title: String) =
        """<programme start="$start +0000" stop="$stop +0000" channel="$channel">""" +
            """<title lang="en">$title</title><desc lang="en">About $title</desc></programme>"""

    @Test
    fun parsesProgrammesKeyedByChannelId() {
        val map = EpgParser.parseStream(
            feed(
                programme("bbc.uk", "20261111120000", "20261111130000", "News"),
                programme("bbc.uk", "20261111130000", "20261111140000", "Film"),
                programme("itv.uk", "20261111120000", "20261111123000", "Quiz")
            )
        )
        assertEquals(2, map.size)
        assertEquals(2, map["bbc.uk"]?.size)
        assertEquals("Film", map["bbc.uk"]?.get(1)?.title)
        assertEquals("Quiz", map["itv.uk"]?.first()?.title)
    }

    @Test
    fun sortsEachChannelByStart() {
        val map = EpgParser.parseStream(
            feed(
                programme("a", "20261111180000", "20261111190000", "Late"),
                programme("a", "20261111120000", "20261111130000", "Early")
            )
        )
        assertEquals(listOf("Early", "Late"), map["a"]?.map { it.title })
    }

    @Test
    fun programmeSplitAcrossLinesStillParses() {
        val split = """
            <programme start="20261111120000 +0000" stop="20261111130000 +0000" channel="x">
              <title>Multi
              line</title>
            </programme>
        """.trimIndent()
        val map = EpgParser.parseStream(feed(split))
        assertEquals(1, map["x"]?.size)
    }

    @Test
    fun channelElementsAreNotProgrammes() {
        val map = EpgParser.parseStream(feed(programme("bbc.uk", "20261111120000", "20261111130000", "News")))
        assertEquals(1, map.size)
        assertTrue(map["bbc.uk"]!!.isNotEmpty())
    }

    @Test
    fun garbageYieldsNothingInsteadOfThrowing() {
        assertTrue(EpgParser.parseStream("<tv><channel id=\"x\"/></tv>".byteInputStream()).isEmpty())
        assertTrue(EpgParser.parseStream("not xml at all".byteInputStream()).isEmpty())
        assertTrue(EpgParser.parseStream("".byteInputStream()).isEmpty())
    }

    @Test
    fun pruneKeepsTheUsefulWindowOnly() {
        val now = 1_800_000_000L
        val map = mapOf(
            "a" to listOf(
                EpgProgram("Ended long ago", "", "", now - 9000, now - 8000),
                EpgProgram("On now", "", "", now - 600, now + 1200),
                EpgProgram("Tomorrow", "", "", now + 86_400, now + 90_000),
                EpgProgram("Next month", "", "", now + 40L * 86_400, now + 40L * 86_400 + 3600)
            )
        )
        val kept = EpgParser.prune(map, now)["a"]?.map { it.title }
        assertEquals(listOf("On now", "Tomorrow"), kept)
    }

    @Test
    fun pruneDropsChannelsWithNothingInTheWindow() {
        val now = 1_800_000_000L
        val map = mapOf("gone" to listOf(EpgProgram("Old", "", "", now - 9000, now - 8000)))
        assertTrue(EpgParser.prune(map, now).isEmpty())
    }
}
