package com.zcc09.iptvplayer.core

import org.junit.Assert.assertTrue
import org.junit.Test

class TsSegmenterTest {

    // ------------------------------------------------------------------ helpers

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

    private fun isPat(data: ByteArray, offset: Int): Boolean {
        if (data.size < offset + 3) return false
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        return data[offset] == 0x47.toByte() && (b1 and 0x40) != 0 && (b1 and 0x1F) == 0 && b2 == 0
    }

    private fun pidAt(data: ByteArray, offset: Int): Int =
        ((data[offset + 1].toInt() and 0x1F) shl 8) or (data[offset + 2].toInt() and 0xFF)

    private fun patPacket(pmtPid: Int): ByteArray {
        val p = packet(0, true, 0xFF.toByte())
        p[4] = 0x00 // pointer_field
        p[5] = 0x00 // table_id PAT
        p[6] = 0xB0.toByte()
        p[7] = 0x0D // section_length = 13
        p[8] = 0x00; p[9] = 0x01 // transport_stream_id
        p[10] = 0xC1.toByte(); p[11] = 0x00; p[12] = 0x00
        p[13] = 0x00; p[14] = 0x01 // program_number 1
        p[15] = (0xE0 or ((pmtPid shr 8) and 0x1F)).toByte()
        p[16] = (pmtPid and 0xFF).toByte()
        return p
    }

    private fun pmtPacket(pmtPid: Int, videoPid: Int, streamType: Int): ByteArray {
        val p = packet(pmtPid, true, 0xFF.toByte())
        p[4] = 0x00
        p[5] = 0x02 // table_id PMT
        p[6] = 0xB0.toByte()
        p[7] = 0x12 // section_length = 18
        p[8] = 0x00; p[9] = 0x01 // program_number
        p[10] = 0xC1.toByte(); p[11] = 0x00; p[12] = 0x00
        p[13] = 0xE0.toByte(); p[14] = 0x00 // PCR_PID
        p[15] = 0x00; p[16] = 0x00 // program_info_length
        p[17] = streamType.toByte()
        p[18] = (0xE0 or ((videoPid shr 8) and 0x1F)).toByte()
        p[19] = (videoPid and 0xFF).toByte()
        p[20] = 0x00; p[21] = 0x00 // es_info_length
        return p
    }

    /** A video TS packet that starts a PES carrying the given NAL units. */
    private fun videoAu(pid: Int, nalTypes: IntArray, hevc: Boolean = false): ByteArray {
        val p = packet(pid, true, 0xFF.toByte())
        p[4] = 0x00; p[5] = 0x00; p[6] = 0x01; p[7] = 0xE0.toByte() // PES start
        p[8] = 0x00; p[9] = 0x00 // PES_packet_length
        p[10] = 0x80.toByte(); p[11] = 0x00
        p[12] = 0x00 // PES_header_data_length
        var i = 13
        for (type in nalTypes) {
            if (i + 8 >= 188) break
            p[i] = 0x00; p[i + 1] = 0x00; p[i + 2] = 0x01
            p[i + 3] = if (hevc) (type shl 1).toByte() else (0x60 or type).toByte()
            p[i + 4] = 0xAA.toByte(); p[i + 5] = 0xAA.toByte()
            p[i + 6] = 0xAA.toByte(); p[i + 7] = 0xAA.toByte()
            i += 8
        }
        return p
    }

    /** NAL types at the head of an access unit, mirroring the segmenter's rules. */
    private fun nalTypes(data: ByteArray, auOffset: Int, hevc: Boolean): List<Int> {
        var s = auOffset + 13
        val end = auOffset + 188
        val out = ArrayList<Int>()
        while (s + 3 < end && out.size < 8) {
            if (data[s] == 0.toByte() && data[s + 1] == 0.toByte() && data[s + 2] == 1.toByte()) {
                out.add(
                    if (hevc) {
                        (data[s + 3].toInt() and 0x7E) shr 1
                    } else {
                        data[s + 3].toInt() and 0x1F
                    }
                )
                s += 4
            } else {
                s++
            }
        }
        return out
    }

    // ------------------------------------------------------------------ fallback mode

    @Test
    fun cutsSegmentsAtPatBoundariesWhenThereIsNoPmt() {
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
            assertTrue("segment ${s.seq} must start with a PAT packet", isPat(s.data, 0))
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

    // ------------------------------------------------------------------ keyframe mode

    @Test
    fun findsVideoPidAndCutsOnlyAtKeyframeAccessUnits() {
        val pmtPid = 0x1000
        val videoPid = 0x0100
        val segmenter = TsSegmenter(
            initialTargetBytes = 188 * 100,
            minSegmentBytes = 188 * 50,
            maxSegments = 40
        )
        val pat = patPacket(pmtPid)
        val pmt = pmtPacket(pmtPid, videoPid, 0x1B)
        val keyAu = videoAu(videoPid, intArrayOf(9, 7, 8, 6, 5)) // AUD SPS PPS SEI IDR
        val plainAu = videoAu(videoPid, intArrayOf(9, 1))         // AUD slice
        val audio = packet(0x101, true, 0x33)

        segmenter.feed(pat, pat.size)
        segmenter.feed(pmt, pmt.size)
        for (i in 0 until 600) {
            val p = if (i % 40 == 0) keyAu else plainAu
            segmenter.feed(p, p.size)
            segmenter.feed(audio, audio.size)
        }
        segmenter.finish()

        assertTrue("mode=${segmenter.modeName}", segmenter.modeName == "keyframe")
        assertTrue("codec=${segmenter.codecName}", segmenter.codecName == "h264")

        val segments = segmenter.snapshot()
        assertTrue("expected several segments, got ${segments.size}", segments.size >= 3)

        for (s in segments) {
            assertTrue("segment ${s.seq} must start with the PAT", isPat(s.data, 0))
            assertTrue(
                "segment ${s.seq} second packet must be the PMT, was ${pidAt(s.data, 188)}",
                pidAt(s.data, 188) == pmtPid
            )
            val types = nalTypes(s.data, 2 * 188, hevc = false)
            assertTrue(
                "segment ${s.seq} must start at a keyframe access unit, nal types=$types",
                types.contains(5) || types.contains(7)
            )
            assertTrue("segment ${s.seq} size=${s.data.size}", s.data.size % 188 == 0)
        }
    }

    @Test
    fun recognisesHevcKeyframes() {
        val pmtPid = 0x1000
        val videoPid = 0x0100
        val segmenter = TsSegmenter(
            initialTargetBytes = 188 * 100,
            minSegmentBytes = 188 * 50,
            maxSegments = 40
        )
        val pat = patPacket(pmtPid)
        val pmt = pmtPacket(pmtPid, videoPid, 0x24) // HEVC
        val keyAu = videoAu(videoPid, intArrayOf(35, 33, 34, 19, 1), hevc = true) // AUD SPS PPS IDR slice
        val plainAu = videoAu(videoPid, intArrayOf(35, 1), hevc = true)

        segmenter.feed(pat, pat.size)
        segmenter.feed(pmt, pmt.size)
        for (i in 0 until 600) {
            val p = if (i % 40 == 0) keyAu else plainAu
            segmenter.feed(p, p.size)
        }
        segmenter.finish()

        assertTrue("codec=${segmenter.codecName}", segmenter.codecName == "hevc")
        val segments = segmenter.snapshot()
        assertTrue("expected several segments, got ${segments.size}", segments.size >= 3)
        for (s in segments) {
            val types = nalTypes(s.data, 2 * 188, hevc = true)
            assertTrue(
                "segment ${s.seq} must start at an HEVC IRAP/SPS unit, nal types=$types",
                types.any { it in 16..23 } || types.contains(33) || types.contains(32)
            )
        }
    }

    @Test
    fun keyframeModeConservesAllBytesPlusPsiOverhead() {
        val pmtPid = 0x1000
        val videoPid = 0x0100
        val segmenter = TsSegmenter(
            initialTargetBytes = 188 * 80,
            minSegmentBytes = 188 * 40,
            maxSegments = 50
        )
        val pat = patPacket(pmtPid)
        val pmt = pmtPacket(pmtPid, videoPid, 0x1B)
        val keyAu = videoAu(videoPid, intArrayOf(9, 7, 5))
        val plainAu = videoAu(videoPid, intArrayOf(9, 1))

        var fed = 0
        for (p in listOf(pat, pmt)) {
            segmenter.feed(p, p.size)
            fed += p.size
        }
        for (i in 0 until 400) {
            val p = if (i % 30 == 0) keyAu else plainAu
            segmenter.feed(p, p.size)
            fed += p.size
        }
        segmenter.finish()

        val segments = segmenter.snapshot()
        val relayed = segments.sumOf { it.data.size }
        // Segments that do not already start with a PAT get PAT+PMT repeated.
        val injectedSegments = segments.count { !isPat(it.data, 0) }
        val overhead = injectedSegments * 2 * 188
        assertTrue(
            "relayed=$relayed fed=$fed overhead=$overhead segments=${segments.size}",
            relayed == fed + overhead
        )
        assertTrue("expected PSI to be repeated on most segments", injectedSegments >= 1)
    }

    // ------------------------------------------------------------------ playlist

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
        assertTrue("needs TARGETDURATION\n$playlist", playlist.contains("#EXT-X-TARGETDURATION:8"))
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
