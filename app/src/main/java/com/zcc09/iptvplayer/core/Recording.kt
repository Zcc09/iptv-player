package com.zcc09.iptvplayer.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live stream capture, fully independent of the player.
 *
 * While recording, a background coroutine opens its own raw HTTP GET on the
 * same live URL the player uses (same headers/User-Agent via [Http]) and
 * appends bytes to a file in the app's external files dir. MPEG-TS needs no
 * finalisation, so a file left behind by a killed app counts as complete.
 *
 * The pure helpers ([safeName], [fileNameFor], [shouldStop]) live here so they
 * can be unit tested on the JVM.
 */
object Recorder {

    enum class StopReason(val wire: String) {
        USER("user"),
        CHANNEL_SWITCH("channelSwitch"),
        PLAYER_CLOSED("playerClosed"),
        SIZE_LIMIT("sizeLimit"),
        DISK_LOW("diskLow"),
        STREAM_ENDED("streamEnded")
    }

    /** What the UI needs while a capture is running. */
    data class Info(val fileName: String, val bytes: Long)

    /** One file in the recordings directory. */
    data class Recording(
        val fileName: String,
        val displayName: String,
        val startedAt: Long,
        val sizeBytes: Long
    )

    const val MAX_FILE_BYTES = 6L * 1024 * 1024 * 1024
    const val MIN_FREE_BYTES = 300L * 1024 * 1024

    private const val DIR_NAME = "recordings"
    private const val BUFFER = 64 * 1024
    private const val TS_PACKET = 188L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<Info?>(null)
    val state: StateFlow<Info?> = _state.asStateFlow()

    @Volatile
    private var job: kotlinx.coroutines.Job? = null
    private var conn: HttpURLConnection? = null
    private var input: InputStream? = null
    private var out: FileOutputStream? = null
    private var activeFile: File? = null
    private var writtenBytes = 0L
    @Volatile
    private var stopRequested: StopReason? = null

    // ---------------------------------------------------------------- pure parts

    /** File-name safety: keep letters, digits and spaces; everything else becomes `_`. */
    fun safeName(name: String): String {
        val mapped = name.map { if (it.isLetterOrDigit() || it == ' ') it else '_' }
            .joinToString("")
            .trim()
        if (mapped.isEmpty() || mapped.all { it == '_' }) return "channel"
        return mapped
    }

    /** Local-time `yyyyMMdd-HHmmss` stamp for the file name. */
    fun stamp(atMillis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(atMillis))

    fun fileNameFor(channelName: String, atMillis: Long): String =
        "${stamp(atMillis)}-${safeName(channelName)}.ts"

    /**
     * Stop decision as a pure function (unit tested): true + the wire reason
     * when the capture must stop. Size limit wins over low free space.
     */
    fun shouldStop(
        fileBytes: Long,
        freeBytes: Long,
        maxFileBytes: Long = MAX_FILE_BYTES,
        minFreeBytes: Long = MIN_FREE_BYTES
    ): Pair<Boolean, String?> {
        if (fileBytes >= maxFileBytes) return true to "sizeLimit"
        if (freeBytes < minFreeBytes) return true to "diskLow"
        return false to null
    }

    /** Strip the `yyyyMMdd-HHmmss-` prefix of a recording file name, if present. */
    fun displayName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        if (base.length > 15 && base[8] == '-' && base[15] == '-') return base.substring(16)
        return base
    }

    /** Parse the `yyyyMMddHHmmss` prefix back into epoch millis (local time). */
    fun parseStartedAt(fileName: String): Long {
        val base = fileName.substringBeforeLast('.')
        if (base.length < 15 || base[8] != '-' || base[15] != '-') return 0L
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT)
        return sdf.parse(base.substring(0, 8) + base.substring(9, 15))?.time ?: 0L
    }

    // ------------------------------------------------------------------ capture

    fun recordingsDir(context: Context): File {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isActive(): Boolean = job != null

    /**
     * Start capturing [url] into a new file. The HTTP connection is opened on
     * the IO dispatcher so a slow server never blocks the UI thread; a failed
     * open clears the state again and logs REC_START_FAILED.
     *
     * Returns false only when a capture is already running.
     */
    @Synchronized
    fun start(context: Context, url: String, channelName: String): Boolean {
        if (job != null) return false
        val dir = recordingsDir(context)
        val file = File(dir, fileNameFor(channelName, System.currentTimeMillis()))
        activeFile = file
        writtenBytes = 0L
        stopRequested = null
        _state.value = Info(file.name, 0L)
        job = scope.launch {
            try {
                val (c, stream) = Http.openStream(url, ua = Repo.userAgent)
                val os = try {
                    FileOutputStream(file)
                } catch (t: Throwable) {
                    runCatching { stream.close() }
                    runCatching { c.disconnect() }
                    throw t
                }
                conn = c
                input = stream
                out = os
                Logx.i("E2E REC start=${file.name}")
                captureLoop()
            } catch (t: Throwable) {
                Logx.w("REC_START_FAILED $url :: ${t.message}")
                runCatching { file.delete() }
                // The E2E REC start line was never logged, so clear the state
                // without a matching stop line.
                conn?.disconnect()
                conn = null
                input = null
                out = null
                job = null
                activeFile = null
                stopRequested = null
                _state.value = null
            }
        }
        return true
    }

    private fun captureLoop() {
        val file = activeFile ?: return
        val dir = file.parentFile
        val buf = ByteArray(BUFFER)
        var written = 0L
        var reason = stopRequested
        var failed = false
        while (reason == null) {
            val stream = input ?: break
            val n = try {
                stream.read(buf)
            } catch (t: Throwable) {
                failed = true
                Logx.e("REC_CAPTURE_ERROR ${file.name}", t)
                break
            }
            if (n < 0) break
            val os = out ?: break
            try {
                os.write(buf, 0, n)
            } catch (t: Throwable) {
                failed = true
                Logx.e("REC_WRITE_ERROR ${file.name}", t)
                break
            }
            written += n
            writtenBytes = written
            val (stop, r) = shouldStop(written, dir?.usableSpace ?: Long.MAX_VALUE)
            if (stop) {
                reason = if (r == "sizeLimit") StopReason.SIZE_LIMIT else StopReason.DISK_LOW
            } else {
                reason = stopRequested
            }
        }
        val finalReason = reason ?: stopRequested ?: StopReason.STREAM_ENDED
        if (written == 0L) {
            // Nothing captured (stream ended instantly or stopped mid-open):
            // a 0-byte .ts is useless in the list.
            runCatching { file.delete() }
        } else if (finalReason == StopReason.STREAM_ENDED && failed && written < TS_PACKET * 16) {
            runCatching { file.delete() }
        }
        finish(finalReason, written)
    }

    /** Ask the running capture to stop; the loop notices via the closed stream. */
    @Synchronized
    fun stop(reason: StopReason) {
        if (activeFile == null) return
        stopRequested = reason
        runCatching { input?.close() }
    }

    /** Stop only when a capture is running (player close / channel switch hooks). */
    fun stopIfActive(reason: StopReason) {
        if (isActive()) stop(reason)
    }

    @Synchronized
    private fun finish(reason: StopReason, bytes: Long) {
        val file = activeFile ?: return
        job = null
        runCatching { out?.close() }
        runCatching { input?.close() }
        runCatching { conn?.disconnect() }
        out = null
        input = null
        conn = null
        activeFile = null
        stopRequested = null
        _state.value = null
        Logx.i("REC stopped ${file.name} bytes=$bytes reason=${reason.wire}")
        Logx.i("E2E REC stop=${file.name} bytes=$bytes reason=${reason.wire}")
    }

    // -------------------------------------------------------------------- files

    /** All recordings, newest first. */
    fun list(context: Context): List<Recording> {
        val dir = recordingsDir(context)
        val files = dir.listFiles { f -> f.isFile } ?: return emptyList()
        return files.map { f ->
            Recording(
                fileName = f.name,
                displayName = displayName(f.name),
                startedAt = parseStartedAt(f.name).takeIf { it > 0 } ?: f.lastModified(),
                sizeBytes = f.length()
            )
        }.sortedByDescending { it.startedAt }
    }

    fun delete(context: Context, fileName: String): Boolean {
        val dir = recordingsDir(context)
        val f = File(dir, fileName)
        return f.exists() && f.isFile && f.delete()
    }

    fun deleteAll(context: Context): Int {
        val dir = recordingsDir(context)
        val files = dir.listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (f.isFile && f.delete()) removed++
        }
        return removed
    }
}
