package com.zcc09.iptvplayer.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Live casting to a DLNA renderer that only starts a *finished* resource.
 *
 * Measured on an LG OLED65BXPVA (2026-11-12): the same MPEG-TS bytes play
 * instantly when served as a complete file, and the set sits in `TRANSITIONING`
 * forever when the identical bytes arrive as an endless stream - with or without
 * Content-Length, DLNA headers, a 2 MB head buffer, a declared 150 MB, or plain
 * HTTP HLS. So this is not a header problem and not a delay problem: this
 * renderer downloads the resource, then plays it.
 *
 * Live television therefore has to be cast as a series of bounded windows:
 * window N is recorded to a file, served with its exact length, and played
 * while window N+1 is recorded. Just before each boundary the app issues
 * Stop -> SetAVTransportURI -> Play (a bare re-cast returns 701, "transition
 * not available"). Playback runs one window (~30 s) behind live.
 */
object CastSession {

    data class State(
        val casting: Boolean = false,
        val device: String = "",
        val window: Int = 0,
        val starting: Boolean = false,
        val error: String = ""
    )

    /** Long enough to watch, short enough that the switch is not a wait. */
    const val WINDOW_SECONDS = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var server: WindowServer? = null

    fun isCastingFor(channelId: String): Boolean =
        _state.value.casting && activeChannelId == channelId

    @Volatile
    private var activeChannelId: String? = null

    fun start(device: DlnaDevice, channel: Channel, userAgent: String) {
        stop()
        val dir = File(Repo.filesDir() ?: return, "cast").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val srv = WindowServer(dir)
        val port = srv.start()
        server = srv
        val base = "http://${Net.lanIp() ?: return}:$port"
        activeChannelId = channel.id
        _state.value = State(starting = true, device = device.name)

        job = scope.launch {
            try {
                // The first window has to be complete before the set will look
                // at it, so this is the one slow step (~WINDOW_SECONDS).
                val first = record(channel, userAgent, dir, 0) ?: run {
                    _state.value = State(error = "Could not read this channel")
                    return@launch
                }
                Dlna.stop(device)
                Dlna.ensureAudible(device)
                val ok = Dlna.play(device, "$base/w/0.ts", channel.name)
                if (!ok) {
                    _state.value = State(error = "The TV would not start this channel")
                    return@launch
                }
                _state.value = State(casting = true, device = device.name, window = 0)
                var n = 0
                while (isActive) {
                    // Record the next window while the current one plays: the
                    // recording runs at real time, so it finishes as the set
                    // reaches the end of what it is playing.
                    val nextFile = record(channel, userAgent, dir, n + 1) ?: break
                    Dlna.stop(device)
                    val switched = Dlna.play(device, "$base/w/${n + 1}.ts", channel.name)
                    if (!switched) break
                    n += 1
                    _state.value = State(casting = true, device = device.name, window = n)
                    // Drop everything but what the set is playing.
                    dir.listFiles()?.forEach { if (it.name != nextFile.name) it.delete() }
                }
            } catch (t: Throwable) {
                Logx.w("CAST_SESSION_FAILED ${t.message}")
                _state.value = State(error = "Casting stopped")
            } finally {
                activeChannelId = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        activeChannelId = null
        server?.stop()
        server = null
        _state.value = State()
        val dev = currentDevice
        if (dev != null) {
            scope.launch { Dlna.stop(dev) }
            currentDevice = null
        }
    }

    @Volatile
    var currentDevice: DlnaDevice? = null

    /** Copy [seconds] of the channel into `w<n>.ts`. Null when nothing arrived. */
    private fun record(channel: Channel, userAgent: String, dir: File, n: Int, seconds: Int = WINDOW_SECONDS): File? {
        val file = File(dir, "w$n.ts")
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        return runCatching {
            val (conn, input) = Http.openStream(channel.url, emptyMap(), 15_000, 20_000, userAgent)
            try {
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (System.nanoTime() < deadline) {
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                    }
                }
            } finally {
                runCatching { input.close() }
                runCatching { conn.disconnect() }
            }
            Logx.i("CAST_WINDOW n=$n bytes=${file.length()}")
            file.takeIf { it.length() > 188 * 10 }
        }.getOrNull()
    }

    /** Serves the recorded windows with an exact Content-Length, which is what the set needs. */
    private class WindowServer(private val dir: File) {

        private var socket: ServerSocket? = null

        fun start(): Int {
            val s = ServerSocket(0)
            socket = s
            Thread {
                while (!s.isClosed) {
                    val client = runCatching { s.accept() }.getOrNull() ?: break
                    Thread { serve(client) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            return s.localPort
        }

        fun stop() {
            runCatching { socket?.close() }
            socket = null
        }

        private fun serve(client: Socket) {
            runCatching {
                client.use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    // Drain the headers so the client's write completes.
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val path = request.split(' ').getOrNull(1).orEmpty()
                    val name = path.substringAfterLast('/').substringBefore('?')
                    val file = dir.listFiles()?.firstOrNull { it.name == name }
                    val out = c.getOutputStream()
                    if (file == null || !file.exists()) {
                        out.write(("HTTP/1.1 404 Not Found" + CRLF + "Content-Length: 0" + CRLF + CRLF)
                            .toByteArray())
                        return@use
                    }
                    val length = file.length()
                    out.write(
                        ("HTTP/1.1 200 OK" + CRLF +
                            "Content-Type: video/mp2t" + CRLF +
                            "Content-Length: $length" + CRLF +
                            "Accept-Ranges: none" + CRLF +
                            "transferMode.dlna.org: Streaming" + CRLF +
                            "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;" +
                            "DLNA.ORG_FLAGS=01700000000000000000000000000000" + CRLF +
                            "Connection: close" + CRLF + CRLF).toByteArray()
                    )
                    file.inputStream().use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                    out.flush()
                    Logx.i("CAST_WINDOW_SERVED $name bytes=$length")
                }
            }
        }

        private companion object {
            const val CRLF = "\r\n"
        }
    }
}
