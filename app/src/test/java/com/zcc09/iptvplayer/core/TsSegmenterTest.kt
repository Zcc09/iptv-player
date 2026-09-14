package com.zcc09.iptvplayer.core

import org.junit.Assert.assertTrue
import org.junit.Test

class TsSegmenterTest {

    private fun packet(pid: Int, payloadStart: Boolean, fill: Byte): ByteArray {
        val p = ByteArray(188)
        p[0] = 0x47
        var b1 = (pid shr 8) and 0x1F
        if (payloadStart) b1 = b1 or 0x40
        p[1] = b1.toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x10
        for (i in 4 until 188) p[i] = fill
        return p
    }

    private fun isPatPacket(data: ByteArray, offset: Int): Boolean {
        if (data.size < offset + 3) return false
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        return data[offset] == 0x47.toByte() && (b1 and 0x40) != 0 && (b1 and 0x1F) == 0 && b2 == 0
    }

    @Test
    fun cutsSegmentsAtPatBoundariesAndKeepsEveryByte() {
        val segmenter = TsSegmenter(
            initialTargetBytes = 188 * 200,
            minSegmentBytes = 188 * 100,
            maxSegments = 40
        )
        val pat = packet(0, true, 0x00)
        val video = packet(0x100, false, 0x42)

        var totalBytes = 0
        for (i in 0 until 3000) {
            val p = if (i % 10 == 0) pat else video
            segmenter.feed(p, p.size)
            totalBytes += p.size
        }
        segmenter.finish()

        val segments = segmenter.snapshot()
        assertTrue("expected several segments, got ${segments.size}", segments.size >= 5)

        var relayed = 0
        for (s in segments) {
            relayed += s.data.size
            assertTrue("segment ${s.seq} not 188 aligned: ${s.data.size}", s.data.size % 188 == 0)
            assertTrue("segment ${s.seq} must start with a PAT packet", isPatPacket(s.data, 0))
            assertTrue("segment ${s.seq} duration=${s.durationSec}", s.durationSec > 0.0)
        }
        assertTrue("relayed $relayed of $totalBytes bytes", relayed == totalBytes)

        val sequenceNumbers = segments.map { it.seq }
        assertTrue("sequence numbers must increase: $sequenceNumbers", sequenceNumbers == sequenceNumbers.sorted())
    }

    @Test
    fun keepsOnlyTheSlidingWindow() {
        val segmenter = TsSegmenter(
            initialTargetBytes = 188 * 100,
            minSegmentBytes = 188 * 50,
            maxSegments = 3
        )
        val pat = packet(0, true, 0x00)
        val video = packet(0x100, false, 0x11)
        for (i in 0 until 2000) {
            val p = if (i % 10 == 0) pat else video
            segmenter.feed(p, p.size)
        }
        segmenter.finish()
        val segments = segmenter.snapshot()
        assertTrue("window must be capped at 3, got ${segments.size}", segments.size <= 3)
        assertTrue("segments produced=${segmenter.producedSegments}", segmenter.producedSegments > segments.size)
    }

    @Test
    fun mediaPlaylistIsWellFormedHls() {
        val segmenter = TsSegmenter(initialTargetBytes = 188 * 100, minSegmentBytes = 188 * 50)
        val pat = packet(0, true, 0x00)
        val video = packet(0x100, false, 0x22)
        for (i in 0 until 1000) {
            val p = if (i % 10 == 0) pat else video
            segmenter.feed(p, p.size)
        }
        segmenter.finish()

        val playlist = segmenter.mediaPlaylist()
        assertTrue("must be an M3U", playlist.startsWith("#EXTM3U"))
        assertTrue("needs EXT-X-VERSION\n$playlist", playlist.contains("#EXT-X-VERSION:3"))
        assertTrue("needs TARGETDURATION\n$playlist", playlist.contains("#EXT-X-TARGETDURATION:6"))
        assertTrue("needs MEDIA-SEQUENCE\n$playlist", playlist.contains("#EXT-X-MEDIA-SEQUENCE:"))
        assertTrue("needs EXTINF\n$playlist", playlist.contains("#EXTINF:"))
        assertTrue("segment uris must be relative\n$playlist", playlist.contains("seg/"))
        assertTrue("live playlist must not be closed with ENDLIST", !playlist.contains("#EXT-X-ENDLIST"))

        val segmentUris = playlist.lines().filter { it.endsWith(".ts") }
        assertTrue("expected segment uris, got $segmentUris", segmentUris.isNotEmpty())
        val sequences = segmenter.snapshot().map { it.seq }
        for (uri in segmentUris) {
            val seq = uri.removePrefix("seg/").removeSuffix(".ts").toLong()
            assertTrue("playlist references missing segment $seq", sequences.contains(seq))
            assertTrue("segment $seq must be fetchable", segmenter.segmentData(seq) != null)
        }
    }

    @Test
    fun awaitSegmentsReturnsImmediatelyWhenBuffered() {
        val segmenter = TsSegmenter(initialTargetBytes = 188 * 50, minSegmentBytes = 188 * 25)
        val pat = packet(0, true, 0x00)
        val video = packet(0x100, false, 0x33)
        for (i in 0 until 500) {
            val p = if (i % 5 == 0) pat else video
            segmenter.feed(p, p.size)
        }
        val started = System.currentTimeMillis()
        val list = segmenter.awaitSegments(1, 5_000)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("should not block when segments exist (${elapsed}ms)", elapsed < 1_000)
        assertTrue("expected segments", list.isNotEmpty())
    }

    @Test
    fun awaitSegmentsTimesOutGracefully() {
        val segmenter = TsSegmenter()
        val started = System.currentTimeMillis()
        val list = segmenter.awaitSegments(3, 1_200)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("expected empty list", list.isEmpty())
        assertTrue("should wait for the timeout, waited ${elapsed}ms", elapsed >= 1_000)
    }
}
