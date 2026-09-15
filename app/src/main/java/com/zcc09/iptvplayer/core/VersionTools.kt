package com.zcc09.iptvplayer.core

/**
 * Pure semver-ish comparison used by the in-app updater.
 *
 * Lives in the Android-free core so it can be unit-tested on the JVM
 * without any Android classes.
 */
object VersionTools {

    /** Splits a version tag like "v1.10.2" into [1, 10, 2], dropping non-numeric parts. */
    fun parseParts(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").removePrefix("V")
        return clean.split('.')
            .map { it.takeWhile { c -> c.isDigit() } }
            .mapNotNull { s -> s.toIntOrNull() }
    }

    /**
     * True when [latest] is strictly newer than [current].
     *
     * Numeric per-component comparison (1.10.0 > 1.9.0). Missing trailing
     * components count as zero (1.2 == 1.2.0). When the numeric prefixes are
     * equal, a plain string tie-break lets pre-release suffixes decide
     * ("1.2.0-rc1" vs "1.2.0" -> neither is newer; "1.2.1" wins).
     */
    fun isNewer(latest: String, current: String): Boolean {
        if (latest.isBlank() || current.isBlank()) return false
        val l = parseParts(latest)
        val c = parseParts(current)
        if (l.isEmpty() || c.isEmpty()) return false
        val maxLen = maxOf(l.size, c.size)
        for (i in 0 until maxLen) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    /** Human size: "14.6 MB" / "512 KB". Locale-pinned for stable output. */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> java.util.Locale.ROOT.let {
            String.format(it, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
        bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
