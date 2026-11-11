package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMergeTest {

    private fun prog(title: String, start: Long) = EpgProgram(
        title = title,
        description = "",
        iconUrl = "",
        startEpochSec = start,
        endEpochSec = start + 1800
    )

    @Test
    fun providerDataWinsForAChannelItCovers() {
        val own = mapOf("bbc.uk" to listOf(prog("Provider News", 1000)))
        val extra = mapOf("bbc.uk" to listOf(prog("Public News", 1000)))
        val merged = EpgMerge.merge(own, extra)
        assertEquals("Provider News", merged["bbc.uk"]?.first()?.title)
    }

    @Test
    fun extraGuideFillsChannelsTheProviderLeavesEmpty() {
        val own = mapOf("bbc.uk" to listOf(prog("Provider News", 1000)))
        val extra = mapOf("mtv.us" to listOf(prog("Public Show", 2000)))
        val merged = EpgMerge.merge(own, extra)
        assertEquals(2, merged.size)
        assertEquals("Public Show", merged["mtv.us"]?.first()?.title)
    }

    @Test
    fun emptyExtraReturnsTheOwnMapUntouched() {
        val own = mapOf("bbc.uk" to listOf(prog("Provider News", 1000)))
        assertSame(own, EpgMerge.merge(own, emptyMap()))
    }

    @Test
    fun emptyProgrammeListsDoNotCreatePhantomChannels() {
        val own = emptyMap<String, List<EpgProgram>>()
        val extra: Map<String, List<EpgProgram>> = mapOf("junk.id" to emptyList())
        assertTrue(EpgMerge.merge(own, extra).isEmpty())
    }

    @Test
    fun aChannelWithAnEmptyOwnListIsFilled() {
        val own: Map<String, List<EpgProgram>> = mapOf("mtv.us" to emptyList())
        val extra: Map<String, List<EpgProgram>> = mapOf("mtv.us" to listOf(prog("Public Show", 2000)))
        assertEquals("Public Show", EpgMerge.merge(own, extra)["mtv.us"]?.first()?.title)
    }
}
