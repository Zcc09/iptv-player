package com.zcc09.iptvplayer.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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
        // Two dialects in the wild: standard XMLTV <programme start= stop= channel=>
        // (what Dispatcharr and most self-hosted panels serve) and the older
        // <epg_program><epg_start><unixtime> shape some XC panels return.
        val xmltv = parseXmltv(xml)
        if (xmltv.isNotEmpty()) return xmltv
        return parseLegacy(xml)
    }

    /**
     * Standard XMLTV: `<programme start="YYYYMMDDHHMMSS ±HHMM" stop="…" channel="id">`.
     * Keyed by the `channel` attribute, which for Xtream-era panels is the
     * `epg_channel_id` the stream list advertises. A missing offset means UTC
     * per the XMLTV spec.
     */
    private fun parseXmltv(xml: String): Map<String, List<EpgProgram>> {
        val byChannel = LinkedHashMap<String, MutableList<EpgProgram>>()
        for (match in XMLTV_PROGRAMME.findAll(xml).toList()) {
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            val channel = attr(attrs, "channel") ?: continue
            val start = xmltvTime(attr(attrs, "start")) ?: continue
            val stop = xmltvTime(attr(attrs, "stop")) ?: continue
            if (stop <= start) continue
            byChannel.getOrPut(channel) { mutableListOf() }.add(
                EpgProgram(
                    title = find(TITLE, body) ?: "",
                    description = find(DESCRIPTION, body) ?: "",
                    iconUrl = find(ICON_SRC, body) ?: "",
                    startEpochSec = start,
                    endEpochSec = stop
                )
            )
        }
        for (programs in byChannel.values) programs.sortBy { it.startEpochSec }
        return byChannel
    }

    /** `<epg_program>` + `<unixtime>` dialect. Keyed by `<stream_id>`. */
    private fun parseLegacy(xml: String): Map<String, List<EpgProgram>> {
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

    private fun attr(attrs: String, name: String): String? =
        Regex("\\b" + name + "\\s*=\\s*[\"']([^\"']*)[\"']")
            .find(attrs)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    /** "20261028113000 +0400" / "20261028113000" -> unix seconds. */
    private fun xmltvTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.trim().takeWhile { it.isDigit() }
        if (digits.length < 14) return null
        val zone = OFFSET.find(raw)?.let {
            val sign = if (it.groupValues[1] == "-") -1 else 1
            ZoneOffset.ofHoursMinutes(sign * it.groupValues[2].toInt(), sign * it.groupValues[3].toInt())
        } ?: ZoneOffset.UTC
        return runCatching {
            LocalDateTime.parse(digits.take(14), XMLTV_FMT).toEpochSecond(zone)
        }.getOrNull()
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
    private val XMLTV_PROGRAMME =
        Regex("(?s)<programme\\b([^>]*)>(.*?)</programme>")
    private val OFFSET = Regex("([+-])(\\d{2})(\\d{2})\\s*$")
    private val XMLTV_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val TITLE =
        Regex("(?s)<title\\b[^>]*>(.*?)</title>")
    private val DESCRIPTION =
        Regex("(?s)<desc(?:ription)?\\b[^>]*>(.*?)</desc(?:ription)?>")
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
