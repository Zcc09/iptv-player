package com.zcc09.iptvplayer.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

/** One scheduled program for a single live stream (channel) of an Xtream account. */
@Serializable
data class EpgProgram(
    val title: String,
    val description: String,
    val iconUrl: String,
    val startEpochSec: Long,
    val endEpochSec: Long
)

/**
 * Pure-JVM parser for the Xtream `get_full_epg` XML response, kept Android-free
 * so it is unit tested:
 *
 *   <epg_programs>
 *     <epg_program>
 *       <title>…</title>
 *       <description>…</description>
 *       <icon src="…"/>
 *       <epg_start><display>…</display><unixtime>1761129600</unixtime></epg_start>
 *       <epg_end><display>…</display><unixtime>1761133200</unixtime></epg_end>
 *       <stream_id>123</stream_id>
 *     </epg_program>
 *   </epg_programs>
 *
 * Panels are sloppy about this format (missing icons, attributes, trailing
 * garbage), so it is parsed with tolerant regexes and the `unixtime` values
 * (never the human-readable `display` strings) drive the schedule. Programs
 * without a stream id or valid times are dropped; any input yields a map, so a
 * server with no EPG simply produces an empty result.
 */
object EpgParser {

    fun parse(xml: String): Map<String, List<EpgProgram>> {
        if (xml.isBlank()) return emptyMap()
        val byStream = LinkedHashMap<String, MutableList<EpgProgram>>()
        for (match in PROGRAM_PATTERN.findAll(xml).toList()) {
            val body = match.groupValues[1]
            val streamId = find(STREAM_ID, body) ?: continue
            val start = findLong(START_TIME, body) ?: continue
            val end = findLong(END_TIME, body) ?: continue
            if (end <= start) continue
            byStream.getOrPut(streamId) { mutableListOf() }.add(
                EpgProgram(
                    title = find(TITLE, body) ?: "",
                    description = find(DESCRIPTION, body) ?: "",
                    iconUrl = find(ICON_SRC, body) ?: "",
                    startEpochSec = start,
                    endEpochSec = end
                )
            )
        }
        for (programs in byStream.values) programs.sortBy { it.startEpochSec }
        return byStream
    }

    /** The program airing at [nowSec] (unix seconds), if any. */
    fun nowAt(programs: List<EpgProgram>, nowSec: Long): EpgProgram? =
        programs.firstOrNull { it.startEpochSec <= nowSec && nowSec < it.endEpochSec }

    /** The earliest program starting after [nowSec], if any. */
    fun nextAt(programs: List<EpgProgram>, nowSec: Long): EpgProgram? =
        programs.firstOrNull { it.startEpochSec > nowSec }

    /** Unwrap CDATA if present (some panels wrap titles/descriptions in it). */
    private fun stripCdata(s: String): String =
        s.replace(Regex("^<!\\[CDATA\\[|\\]\\]>?$"), "")

    /** "HH:mm" in the device's local time zone (pure, for OSD and guide labels). */
    fun formatHm(epochSec: Long): String =
        HM_FORMAT.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

    private fun find(pattern: Regex, body: String): String? =
        pattern.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.let { stripCdata(it) }
            ?.let { if (it.isBlank()) null else decodeEntities(it) }

    private fun findLong(pattern: Regex, body: String): Long? =
        pattern.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun decodeEntities(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    private val PROGRAM_PATTERN =
        Regex("(?s)<epg_program\\b[^>]*>(.*?)</epg_program>")
    private val TITLE =
        Regex("(?s)<title\\b[^>]*>(.*?)</title>")
    private val DESCRIPTION =
        Regex("(?s)<description\\b[^>]*>(.*?)</description>")
    private val ICON_SRC =
        Regex("<icon[^>]*\\bsrc\\s*=\\s*[\"']([^\"']*)[\"']")
    private val START_TIME =
        Regex("(?s)<epg_start\\b[^>]*>.*?<unixtime>(\\d+)</unixtime>")
    private val END_TIME =
        Regex("(?s)<epg_end\\b[^>]*>.*?<unixtime>(\\d+)</unixtime>")
    private val STREAM_ID =
        Regex("<stream_id\\b[^>]*>([^<]+)</stream_id>")
    private val HM_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
}
