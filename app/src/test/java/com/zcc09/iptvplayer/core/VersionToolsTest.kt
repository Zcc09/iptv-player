package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionToolsTest {

    @Test
    fun `parses numeric parts and drops the v prefix`() {
        assertEquals(listOf(1, 10, 2), VersionTools.parseParts("v1.10.2"))
        assertEquals(listOf(1, 2), VersionTools.parseParts("V1.2"))
        assertEquals(listOf(1, 2, 0), VersionTools.parseParts("1.2.0"))
    }

    @Test
    fun `ignores non-numeric suffixes inside a component`() {
        assertEquals(listOf(1, 2, 0), VersionTools.parseParts("1.2.0-rc1"))
        assertEquals(listOf(2, 4, 20), VersionTools.parseParts("2.4.20"))
    }

    @Test
    fun `compares numerically, not lexicographically`() {
        assertTrue(VersionTools.isNewer("1.10.0", "1.9.0"))
        assertTrue(VersionTools.isNewer("2.0.0", "1.99.99"))
        assertFalse(VersionTools.isNewer("1.9.0", "1.10.0"))
        assertFalse(VersionTools.isNewer("1.2.0", "1.2.0"))
        assertFalse(VersionTools.isNewer("1.2.1", "1.2.2"))
    }

    @Test
    fun `missing trailing components count as zero`() {
        assertFalse(VersionTools.isNewer("1.2", "1.2.0"))
        assertFalse(VersionTools.isNewer("1.2.0", "1.2"))
        assertTrue(VersionTools.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun `handles v prefix on either side`() {
        assertTrue(VersionTools.isNewer("v1.1.0", "1.0.0"))
        assertTrue(VersionTools.isNewer("1.1.0", "v1.0.0"))
        assertFalse(VersionTools.isNewer("v1.1.0", "v1.1.0"))
    }

    @Test
    fun `blank or garbage versions are never "newer"`() {
        assertFalse(VersionTools.isNewer("", "1.0.0"))
        assertFalse(VersionTools.isNewer("1.0.0", ""))
        assertFalse(VersionTools.isNewer("abc", "1.0.0"))
        assertFalse(VersionTools.isNewer("1.0.0", "abc"))
    }

    @Test
    fun `real release sequence`() {
        assertTrue(VersionTools.isNewer("1.1.0", "1.0.0"))
        assertTrue(VersionTools.isNewer("1.2.0", "1.1.0"))
        assertTrue(VersionTools.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `formats byte sizes`() {
        assertEquals("512 B", VersionTools.formatBytes(512))
        assertEquals("1 KB", VersionTools.formatBytes(1024))
        assertEquals("14.6 MB", VersionTools.formatBytes((14.6 * 1024 * 1024).toLong()))
    }
}
