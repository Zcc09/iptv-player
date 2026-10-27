package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTest {

    // ------------------------------------------------------------ file names

    @Test
    fun safeNameKeepsLettersDigitsAndSpaces() {
        assertEquals("BBC One", Recorder.safeName("BBC One"))
        assertEquals("AR 2", Recorder.safeName("AR 2"))
    }

    @Test
    fun safeNameReplacesEverythingElseWithUnderscore() {
        assertEquals("BBC_One_", Recorder.safeName("BBC/One!"))
        assertEquals("AR_ EMIRATES 4K", Recorder.safeName("AR| EMIRATES 4K"))
        assertEquals("a_b_c", Recorder.safeName("a/b\\c"))
    }

    @Test
    fun safeNameTrimsAndFallsBackForBlankInput() {
        assertEquals("ABC", Recorder.safeName("  ABC  "))
        assertEquals("channel", Recorder.safeName(""))
        assertEquals("channel", Recorder.safeName("///"))
    }

    @Test
    fun fileNameHasTimestampStampAndTsExtension() {
        val name = Recorder.fileNameFor("BBC One", 1_761_550_000_000L)
        assertTrue(
            "got $name",
            name.matches(Regex("\\d{8}-\\d{6}-BBC One\\.ts"))
        )
    }

    @Test
    fun displayNameStripsTheTimestampPrefix() {
        assertEquals("BBC One", Recorder.displayName("20261027-192530-BBC One.ts"))
        // No timestamp prefix -> the whole base name stays
        assertEquals("leftover", Recorder.displayName("leftover.ts"))
    }

    @Test
    fun startedAtRoundTripsThroughTheFileName() {
        val at = 1_761_550_000_000L
        val name = Recorder.fileNameFor("BBC One", at)
        assertEquals(at, Recorder.parseStartedAt(name))
    }

    @Test
    fun startedAtIsZeroForForeignNames() {
        assertEquals(0L, Recorder.parseStartedAt("leftover.ts"))
    }

    // ------------------------------------------------------- stop decision

    @Test
    fun underBothLimitsKeepsRecording() {
        val (stop, reason) = Recorder.shouldStop(
            fileBytes = 1L * 1024 * 1024,
            freeBytes = 1L * 1024 * 1024 * 1024
        )
        assertFalse(stop)
        assertEquals(null, reason)
    }

    @Test
    fun fileReachingTheSizeLimitStopsWithSizeLimit() {
        val (stop, reason) = Recorder.shouldStop(
            fileBytes = Recorder.MAX_FILE_BYTES,
            freeBytes = 1L * 1024 * 1024 * 1024
        )
        assertTrue(stop)
        assertEquals("sizeLimit", reason)
    }

    @Test
    fun lowFreeDiskStopsWithDiskLow() {
        val (stop, reason) = Recorder.shouldStop(
            fileBytes = 1L * 1024 * 1024,
            freeBytes = Recorder.MIN_FREE_BYTES - 1
        )
        assertTrue(stop)
        assertEquals("diskLow", reason)
    }

    @Test
    fun exactlyAtTheFreeSpaceFloorStillRecords() {
        // The rule is "free disk < 300 MB", so exactly 300 MB still records.
        val (stop, _) = Recorder.shouldStop(
            fileBytes = 0L,
            freeBytes = Recorder.MIN_FREE_BYTES
        )
        assertFalse(stop)
    }

    @Test
    fun sizeLimitWinsOverLowFreeSpace() {
        val (stop, reason) = Recorder.shouldStop(
            fileBytes = Recorder.MAX_FILE_BYTES + 188,
            freeBytes = 0L
        )
        assertTrue(stop)
        assertEquals("sizeLimit", reason)
    }

    @Test
    fun customLimitsAreHonoured() {
        val (stop, reason) = Recorder.shouldStop(
            fileBytes = 5,
            freeBytes = 5,
            maxFileBytes = 4,
            minFreeBytes = 6
        )
        assertTrue(stop)
        assertEquals("sizeLimit", reason)
    }
}
