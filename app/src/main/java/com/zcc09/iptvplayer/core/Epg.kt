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
     * Parse an XMLTV document straight off a stream.
     *
     * Public guides inflate to 50 MB and more, which a whole-string parse turns
     * into a single ~190 MB allocation - enough to fail on a TV box, and it did
     * fail on the E2E emulator ("Failed to allocate a 191135752 byte
     * allocation"). Elements are consumed one at a time, so only the programme
     * currently being read is buffered.
     */
    fun parseStream(input: java.io.InputStream): Map<String, List<EpgProgram>> {
        val byChannel = LinkedHashMap<String, MutableList<EpgProgram>>()
        val reader = java.io.BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        var pending = StringBuilder(4096)
        reader.useLines { lines ->
            for (line in lines) {
                // Most files are one programme per line: skip the rest cheaply.
                if (pending.isEmpty() && !line.contains("<programme")) continue
                pending.append(line).append('\n')
                while (true) {
                    val close = pending.indexOf("</programme>")
                    if (close < 0) break
                    val end = close + "</programme>".length
                    val open = pending.lastIndexOf("<programme", close)
                    if (open >= 0) {
                        parseXmltvElement(pending.substring(open, end))?.let { (channel, program) ->
                            byChannel.getOrPut(channel) { mutableListOf() }.add(program)
                        }
                    }
                    pending.delete(0, end)
                }
                // A malformed file must not grow this buffer without bound.
                if (pending.length > 2 * 1024 * 1024) pending = StringBuilder(4096)
            }
        }
        for (programs in byChannel.values) programs.sortBy { it.startEpochSec }
        return byChannel
    }

    /** One `<programme …>…</programme>` element, as the streaming reader sees it. */
    private fun parseXmltvElement(element: String): Pair<String, EpgProgram>? {
        val match = XMLTV_PROGRAMME.find(element) ?: return null
        val attrs = match.groupValues[1]
        val body = match.groupValues[2]
        val channel = attr(attrs, "channel") ?: return null
        val start = xmltvTime(attr(attrs, "start")) ?: return null
        val stop = xmltvTime(attr(attrs, "stop")) ?: return null
        if (stop <= start) return null
        return channel to EpgProgram(
            title = find(TITLE, body) ?: "",
            description = find(DESCRIPTION, body) ?: "",
            iconUrl = find(ICON_SRC, body) ?: "",
            startEpochSec = start,
            endEpochSec = stop
        )
    }

    /**
     * Keep only what a guide screen can use: programmes that ended in the last
     * two hours through the next seven days. Public feeds carry far more, and
     * every held programme costs memory on a device with little of it.
     */
    fun prune(
        programs: Map<String, List<EpgProgram>>,
        nowSec: Long = System.currentTimeMillis() / 1000
    ): Map<String, List<EpgProgram>> {
        if (programs.isEmpty()) return programs
        val from = nowSec - 2 * 60 * 60
        val to = nowSec + 7 * 24 * 60 * 60
        val out = LinkedHashMap<String, List<EpgProgram>>(programs.size)
        for ((channel, list) in programs) {
            val kept = list.filter { it.endEpochSec > from && it.startEpochSec < to }
            if (kept.isNotEmpty()) out[channel] = kept
        }
        return out
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
