package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZapTest {

    private fun ch(number: Int, name: String = "ch$number") = Channel(
        id = "id-$number",
        playlistId = "p",
        name = name,
        number = number
    )

    private val channels = listOf(ch(1), ch(7), ch(28), ch(101))

    @Test
    fun exactNumberMatches() {
        assertEquals("ch28", Zap.channelFor(channels, "28")?.name)
    }

    @Test
    fun leadingZerosAreIgnored() {
        assertEquals("ch7", Zap.channelFor(channels, "007")?.name)
    }

    @Test
    fun unknownNumberMatchesNothing() {
        assertNull(Zap.channelFor(channels, "999"))
    }

    @Test
    fun emptyOrZeroBufferMatchesNothing() {
        assertNull(Zap.channelFor(channels, ""))
        assertNull(Zap.channelFor(channels, "0"))
        assertNull(Zap.channelFor(channels, "000"))
    }

    @Test
    fun channelsWithoutNumbersNeverMatch() {
        val unnumbered = listOf(Channel(id = "a", playlistId = "p", name = "No number"))
        assertNull(Zap.channelFor(unnumbered, "1"))
    }

    @Test
    fun pushCapsTheBufferAndIgnoresNonDigits() {
        assertEquals("12", Zap.push("1", '2'))
        assertEquals("1234", Zap.push("1234", '5'))
        assertEquals("12", Zap.push("12", 'x'))
    }
}
