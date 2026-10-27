package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide only works when the panel's `get_full_epg` XML is parsed into the
 * right per-stream schedules, so pin the tolerant parsing: entities and CDATA
 * must be unwrapped, `unixtime` (not `display`) must drive the schedule, and
 * broken entries must be dropped without breaking the rest of the response.
 */
class EpgParserTest {

    private val sample =
        "<epg_programs>" +
            "<epg_program>" +
            "<title>News at One</title>" +
            "<description>&lt;The &amp; main news bulletin&gt;</description>" +
            "<icon src=\"https://panel.example/img/ch12.png\"/>" +
            "<epg_start><display>Mon 01:00</display><unixtime>1761129600</unixtime></epg_start>" +
            "<epg_end><display>Mon 02:00</display><unixtime>1761133200</unixtime></epg_end>" +
            "<stream_id>12</stream_id>" +
            "</epg_program>" +
            "<epg_program>" +
            "<title><![CDATA[Second <Show>]]></title>" +
            "<description></description>" +
            "<epg_start><display>Mon 02:00</display><unixtime>1761133200</unixtime></epg_start>" +
            "<epg_end><display>Mon 03:00</display><unixtime>1761136800</unixtime></epg_end>" +
            "<stream_id>12</stream_id>" +
            "</epg_program>" +
            "<epg_program>" +
            "<title>News at Two</title>" +
            "<epg_start><display>Mon 02:00</display><unixtime>1761133200</unixtime></epg_start>" +
            "<epg_end><display>Mon 03:00</display><unixtime>1761136800</unixtime></epg_end>" +
            "<stream_id>77</stream_id>" +
            "</epg_program>" +
            "<epg_program>" +
            "<title>Inverted times</title>" +
            "<epg_start><display>Mon 03:00</display><unixtime>1761136800</unixtime></epg_start>" +
            "<epg_end><display>Mon 02:00</display><unixtime>1761133200</unixtime></epg_end>" +
            "<stream_id>5</stream_id>" +
            "</epg_program>" +
            "<epg_program>" +
            "<title>Missing stream id</title>" +
            "<epg_start><display>Mon 01:00</display><unixtime>1761129600</unixtime></epg_start>" +
            "<epg_end><display>Mon 02:00</display><unixtime>1761133200</unixtime></epg_end>" +
            "</epg_program>" +
            "</epg_programs>"

    @Test
    fun `programs group by stream id and sort by start`() {
        val map = EpgParser.parse(sample)
        assertEquals(setOf("12", "77"), map.keys)
        val ch12 = map["12"]!!
        assertEquals(2, ch12.size)
        assertEquals("News at One", ch12[0].title)
        assertEquals(1761129600L, ch12[0].startEpochSec)
        assertEquals(1761133200L, ch12[0].endEpochSec)
        assertEquals("News at Two", map["77"]!![0].title)
    }

    @Test
    fun `entities and cdata are unwrapped`() {
        val ch12 = EpgParser.parse(sample)["12"]!!
        assertEquals("<The & main news bulletin>", ch12[0].description)
        assertEquals("Second <Show>", ch12[1].title)
    }

    @Test
    fun `icon url is kept when present and blank otherwise`() {
        val map = EpgParser.parse(sample)
        assertEquals("https://panel.example/img/ch12.png", map["12"]!![0].iconUrl)
        assertEquals("", map["12"]!![1].iconUrl)
    }

    @Test
    fun `programs with inverted times or no stream id are dropped`() {
        val map = EpgParser.parse(sample)
        assertNull(map["5"])
        assertEquals(3, map.values.sumOf { it.size })
    }

    @Test
    fun `garbage and empty input yields an empty map, not an error`() {
        assertTrue(EpgParser.parse("").isEmpty())
        assertTrue(EpgParser.parse("   ").isEmpty())
        assertTrue(EpgParser.parse("not xml at all").isEmpty())
        assertTrue(EpgParser.parse("<epg_programs></epg_programs>").isEmpty())
        assertTrue(EpgParser.parse("<html><body>404 not found</body></html>").isEmpty())
    }

    @Test
    fun `now and next resolve against unixtime`() {
        val programs = EpgParser.parse(sample)["12"]!!
        val now = 1761131000L
        assertEquals("News at One", EpgParser.nowAt(programs, now)?.title)
        assertEquals("Second <Show>", EpgParser.nextAt(programs, now)?.title)
        // Past the last program there is neither a current nor a next one.
        assertNull(EpgParser.nowAt(programs, 1761140000L))
        assertNull(EpgParser.nextAt(programs, 1761140000L))
    }

    @Test
    fun `programs at exact boundaries belong to the running one`() {
        val programs = EpgParser.parse(sample)["12"]!!
        // Exactly at the boundary, the second program is now and the first is past.
        assertEquals("Second <Show>", EpgParser.nowAt(programs, 1761133200L)?.title)
        assertNull(EpgParser.nowAt(programs, 1761129599L))
    }
}
