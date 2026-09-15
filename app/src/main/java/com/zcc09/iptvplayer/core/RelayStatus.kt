package com.zcc09.iptvplayer.core

/**
 * Human-readable view of the HLS relay's status JSON.
 *
 * `RelayManager.status()` returns a JSON blob because the end-to-end suite
 * asserts on it. The settings screen used to print that blob verbatim, so users
 * saw `HLS relay: {"running":false,"port":0,...}`. This turns it into a sentence.
 *
 * Android-free on purpose: the local JVM harness unit-tests it.
 */
object RelayStatus {

    /** Pulls one value out of the flat status JSON the relay emits. */
    private fun num(json: String, key: String): Long =
        Regex("\"$key\":\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun str(json: String, key: String): String =
        Regex("\"$key\":\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""

    fun isRunning(json: String): Boolean =
        Regex("\"running\":\\s*(true|false)").find(json)?.groupValues?.get(1) == "true"

    /** e.g. "Stopped" or "Running on port 40189 — 12.4 MB delivered, 87 segments, keyframe". */
    fun describe(json: String): String {
        if (!isRunning(json)) {
            val error = str(json, "error")
            return if (error.isNotBlank()) "Stopped ($error)" else "Stopped"
        }
        val port = num(json, "port")
        val bytes = num(json, "bytesIn")
        val segments = num(json, "segments")
        val mode = str(json, "mode").ifBlank { "unknown" }
        val parts = mutableListOf<String>()
        if (bytes > 0) parts += "${VersionTools.formatBytes(bytes)} delivered"
        parts += "$segments segments"
        parts += mode
        val where = if (port > 0) "Running on port $port" else "Running"
        return "$where — " + parts.joinToString(", ")
    }
}
