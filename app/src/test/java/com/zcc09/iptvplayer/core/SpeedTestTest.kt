package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestTest {

    @Test
    fun mbpsShowsOneDecimalBelow100() {
        assertEquals("12.4", SpeedTest.formatMbps(12.44))
        assertEquals("0.8", SpeedTest.formatMbps(0.75))
        assertEquals("99.9", SpeedTest.formatMbps(99.94))
    }

    @Test
    fun mbpsRoundsAbove100() {
        assertEquals("142", SpeedTest.formatMbps(142.37))
        assertEquals("1000", SpeedTest.formatMbps(1000.0))
    }

    @Test
    fun sizesUseTheLargestUnitThatFits() {
        assertEquals("512 B", SpeedTest.formatSize(512))
        assertEquals("2 KB", SpeedTest.formatSize(2048))
        assertEquals("6.2 MB", SpeedTest.formatSize((6.2 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", SpeedTest.formatSize((2L * 1024 * 1024 * 1024)))
    }

    @Test
    fun aSuccessfulResultReadsAsOneLine() {
        val r = SpeedResult(latencyMs = 85, mbps = 12.44, bytes = 6_500_000, seconds = 5.0, url = "http://x")
        assertEquals("12.4 Mbps  ·  85 ms  ·  6.2 MB in 5.0 s", SpeedTest.describe(r))
    }

    @Test
    fun aFailedResultSaysWhy() {
        val r = SpeedResult(0, 0.0, 0, 0.0, "http://x", "HTTP 403")
        assertFalse(r.ok)
        assertEquals("Speed test failed: HTTP 403", SpeedTest.describe(r))
    }

    @Test
    fun aResultWithNoBytesIsNotOk() {
        assertFalse(SpeedResult(50, 0.0, 0, 0.0, "http://x").ok)
        assertTrue(SpeedResult(50, 1.0, 1024, 1.0, "http://x").ok)
    }
}
