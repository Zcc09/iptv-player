package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayStatusTest {

    /** Exactly what RelayManager emits when nothing has been started. */
    private val stopped =
        "{\"running\":false,\"port\":0,\"bytesIn\":0,\"clients\":0,\"segments\":0," +
            "\"mode\":\"none\",\"codec\":\"-\",\"upstreamCode\":0,\"error\":\"\"}"

    private val running =
        "{\"running\":true,\"port\":40189,\"bytesIn\":13002342,\"clients\":1,\"segments\":87," +
            "\"mode\":\"keyframe\",\"codec\":\"H.264\",\"upstreamCode\":200,\"error\":\"\"}"

    @Test
    fun `reports stopped without leaking json`() {
        val text = RelayStatus.describe(stopped)
        assertEquals("Stopped", text)
        assertFalse("no braces should survive", text.contains("{"))
        assertFalse(text.contains("running"))
    }

    @Test
    fun `describes a running relay in words`() {
        val text = RelayStatus.describe(running)
        assertTrue(text, text.startsWith("Running on port 40189"))
        assertTrue(text, text.contains("12.4 MB delivered"))
        assertTrue(text, text.contains("87 segments"))
        assertTrue(text, text.contains("keyframe"))
        assertFalse(text.contains("{"))
    }

    @Test
    fun `surfaces the last error when stopped`() {
        val withError =
            "{\"running\":false,\"port\":0,\"bytesIn\":0,\"clients\":0,\"segments\":0," +
                "\"mode\":\"none\",\"codec\":\"-\",\"upstreamCode\":0,\"error\":\"connect timed out\"}"
        assertEquals("Stopped (connect timed out)", RelayStatus.describe(withError))
    }

    @Test
    fun `tolerates missing fields and garbage`() {
        assertEquals("Stopped", RelayStatus.describe(""))
        assertEquals("Stopped", RelayStatus.describe("not json at all"))
        val partial = "{\"running\":true,\"port\":1234}"
        val text = RelayStatus.describe(partial)
        assertTrue(text, text.startsWith("Running on port 1234"))
        assertTrue(text, text.contains("0 segments"))
    }

    @Test
    fun `running flag is read independently of the rest`() {
        assertTrue(RelayStatus.isRunning("{\"running\":true}"))
        assertFalse(RelayStatus.isRunning("{\"running\":false}"))
        assertFalse(RelayStatus.isRunning("{}"))
    }
}
