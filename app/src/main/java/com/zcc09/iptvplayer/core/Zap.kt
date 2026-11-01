package com.zcc09.iptvplayer.core

/**
 * Digit-buffer channel lookup ("numeric zap").
 *
 * Keeping the rule here rather than inside the player means the same logic is
 * unit tested and reused by the debug drivers, instead of being re-derived.
 */
object Zap {

    /** Longest buffer we accept, so a stuck key cannot grow it forever. */
    const val MAX_DIGITS = 4

    /**
     * The channel [buffer] refers to, or null when nothing matches.
     *
     * Matching is by channel number (M3U `tvg-chno` / Xtream `num`); leading
     * zeros are ignored because remotes produce "007" as readily as "7".
     * A playlist whose channels carry no numbers simply never matches.
     */
    fun channelFor(channels: List<Channel>, buffer: String): Channel? {
        val digits = buffer.trim().trimStart('0')
        if (digits.isEmpty()) return null
        val n = digits.toIntOrNull() ?: return null
        if (n <= 0) return null
        return channels.firstOrNull { it.number == n }
    }

    /** Append one digit to a buffer, capped at [MAX_DIGITS]. */
    fun push(buffer: String, digit: Char): String {
        if (!digit.isDigit()) return buffer
        return (buffer + digit).take(MAX_DIGITS)
    }
}
