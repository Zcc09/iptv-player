package com.zcc09.iptvplayer.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.HttpURLConnection

/**
 * A TV that speaks UPnP/DLNA AVTransport.
 *
 * Chromecast is a Google protocol; LG (webOS), Samsung (Tizen), Sony, Philips,
 * Panasonic and most other network TVs instead expose DLNA, which is plain
 * HTTP + SOAP over UPnP discovery. That makes "cast to any TV" a matter of
 * SSDP, one device-description fetch, and two SOAP calls - no vendor SDK and no
 * extra dependency.
 */
data class DlnaDevice(
    val name: String,
    val location: String,
    val controlUrl: String,
    val model: String = "",
    /** RenderingControl (volume/mute). Not every renderer exposes it. */
    val renderingUrl: String = ""
) {
    /** Stable-ish identity for de-duplicating discovery responses. */
    val id: String get() = controlUrl.ifBlank { location }
}

/** SSDP discovery framing. Pure string work, so it is unit tested. */
object Ssdp {

    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /** MediaRenderer is the AVTransport role: the thing that plays a URL. */
    const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

    fun searchMessage(mx: Int = 2): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $ADDRESS:$PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $mx\r\n")
        append("ST: $SEARCH_TARGET\r\n")
        append("USER-AGENT: ${Http.DEFAULT_UA}\r\n")
        append("\r\n")
    }

    /** Lower-cased header map, or null when the response is not a 200. */
    fun headers(raw: String): Map<String, String>? {
        val lines = raw.split("\r\n", "\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        if (!lines.first().uppercase().contains("200 OK")) return null
        val map = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            map[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return map
    }

    fun locationOf(raw: String): String? = headers(raw)?.get("location")?.takeIf { it.startsWith("http") }
}

/**
 * Minimal UPnP device-description reader: find the AVTransport service and its
 * control URL, resolved against the description's own URL (descriptions often
 * return a relative `/upnp/control/AVTransport1`).
 */
object DlnaDescription {

    private val AV_SERVICE = Regex(
        "<serviceType>\\s*urn:schemas-upnp-org:service:AVTransport:1\\s*</serviceType>" +
            "[\\s\\S]{0,600}?<controlURL>([^<]+)</controlURL>",
        RegexOption.IGNORE_CASE
    )
    private val SERVICES = Regex("<service>([\\s\\S]*?)</service>", RegexOption.IGNORE_CASE)
    private val CONTROL_URL = Regex("<controlURL>([^<]+)</controlURL>", RegexOption.IGNORE_CASE)
    private val FRIENDLY_NAME = Regex("<friendlyName>([^<]*)</friendlyName>", RegexOption.IGNORE_CASE)
    private val MODEL_NAME = Regex("<modelName>([^<]*)</modelName>", RegexOption.IGNORE_CASE)

    /** Control URL of the service block whose serviceType equals [serviceType]. */
    fun controlUrlFor(xml: String, location: String, serviceType: String): String? {
        for (block in SERVICES.findAll(xml)) {
            val body = block.groupValues[1]
            if (!body.contains(serviceType)) continue
            val url = CONTROL_URL.find(body)?.groupValues?.get(1)?.trim().orEmpty()
            if (url.isNotEmpty()) return resolve(location, url)
        }
        return null
    }

    fun controlUrl(xml: String, location: String): String? {
        val raw = AV_SERVICE.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return resolve(location, raw)
    }

    fun friendlyName(xml: String): String =
        FRIENDLY_NAME.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "Network TV"

    fun modelName(xml: String): String =
        MODEL_NAME.find(xml)?.groupValues?.get(1)?.trim().orEmpty()

    /** Absolute-ise a possibly relative control URL against the description URL. */
    fun resolve(base: String, path: String): String = runCatching {
        if (path.startsWith("http://") || path.startsWith("https://")) path else URL(URL(base), path).toString()
    }.getOrDefault(path)
}

/** AVTransport SOAP envelopes and their error detection. */
object DlnaSoap {

    private const val NS = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SET_URI_ACTION = "$NS#SetAVTransportURI"
    const val PLAY_ACTION = "$NS#Play"
    const val STOP_ACTION = "$NS#Stop"

    /** RenderingControl shares the envelope shape but not the service namespace. */
    private const val RENDERING_NS = "urn:schemas-upnp-org:service:RenderingControl:1"

    fun renderingBody(action: String, inner: String): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"$RENDERING_NS\">")
            append(inner)
            append("</u:$action></s:Body></s:Envelope>")
        }

    private fun envelope(action: String, body: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:$action xmlns:u=\"$NS\">")
        append(body)
        append("</u:$action></s:Body></s:Envelope>")
    }

    fun setUriBody(url: String, metadata: String = ""): String = envelope(
        "SetAVTransportURI",
        "<InstanceID>0</InstanceID><CurrentURI>${escape(url)}</CurrentURI>" +
            "<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>"
    )

    fun playBody(): String = envelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    fun stopBody(): String = envelope("Stop", "<InstanceID>0</InstanceID>")

    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** UPnP answers errors with a 500 carrying `<errorCode>`; success has `errorCode 0` or none. */
    fun failed(statusCode: Int, body: String): Boolean {
        if (statusCode in 200..299) return false
        val code = Regex("<errorCode>\\s*(\\d+)\\s*</errorCode>").find(body)?.groupValues?.get(1)
        return code == null || code != "0"
    }
}

object Dlna {

    /**
     * Broadcast an M-SEARCH and collect renderers. Each response is only a
     * pointer, so the description is fetched to find the AVTransport control URL.
     */
    suspend fun discover(
        timeoutMs: Long = 4000,
        fetchDetails: Boolean = true
    ): List<DlnaDevice> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val found = LinkedHashMap<String, DlnaDevice>()
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = 300
            val msg = Ssdp.searchMessage().toByteArray(Charsets.UTF_8)
            val multicast = InetSocketAddress(InetAddress.getByName(Ssdp.ADDRESS), Ssdp.PORT)
            repeat(3) {
                runCatching { socket.send(DatagramPacket(msg, msg.size, multicast)) }
            }
            // LG webOS (verified on an OLED65BXPVA) answers a *unicast* M-SEARCH
            // and ignores the multicast one, so a multicast-only search finds
            // nothing on those sets. Sweep the local /24 as well.
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)

            // Phase 1: multicast window.
            val multicastDeadline = System.currentTimeMillis() + (timeoutMs / 2)
            while (System.currentTimeMillis() < multicastDeadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                collect(packet, found, fetchDetails)
            }

            // Phase 2: unicast to every host on our own subnet.
            // Net.lanIp() skips cellular (rmnet/ccmni) and VPN tunnels; a plain
            // "first IPv4" picked rmnet_data2 on a real phone and swept the
            // carrier's subnet, finding nothing.
            val localIp = runCatching { Net.lanIp() }.getOrNull()
            if (localIp != null) {
                val prefix = localIp.substringBeforeLast('.')
                for (host in 1..254) {
                    val address = "$prefix.$host"
                    if (address == localIp) continue
                    runCatching {
                        socket.send(DatagramPacket(msg, msg.size, InetSocketAddress(address, Ssdp.PORT)))
                    }
                }
                socket.soTimeout = 400
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: IOException) {
                        break
                    }
                    collect(packet, found, fetchDetails)
                }
            }
        } finally {
            runCatching { socket.close() }
        }
        found.values.toList()
    }

    private fun collect(packet: DatagramPacket, found: MutableMap<String, DlnaDevice>, fetchDetails: Boolean) {
        val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val location = Ssdp.locationOf(raw) ?: return
        if (found.containsKey(location)) return
        val device = if (fetchDetails) {
            describe(location)
        } else {
            DlnaDevice(name = "Network TV", location = location, controlUrl = "")
        }
        if (device != null) found[location] = device
    }

    /** Fetch and read a device description. Null when it does not expose AVTransport. */
    fun describe(location: String): DlnaDevice? = runCatching {
        val xml = Http.getText(location, timeoutMs = 5000)
        val control = DlnaDescription.controlUrl(xml, location) ?: return null
        DlnaDevice(
            name = DlnaDescription.friendlyName(xml),
            location = location,
            controlUrl = control,
            model = DlnaDescription.modelName(xml),
            renderingUrl = DlnaDescription.controlUrlFor(
                xml, location, "urn:schemas-upnp-org:service:RenderingControl:1"
            ).orEmpty()
        )
    }.getOrNull()

    /** Point the TV at [url] and start it. Returns true only when both calls succeed. */
    suspend fun play(device: DlnaDevice, url: String, title: String = ""): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (device.controlUrl.isBlank()) return@withContext false
            val metadata = didl(title, url)
            val set = soap(
                device.controlUrl, DlnaSoap.SET_URI_ACTION, DlnaSoap.setUriBody(url, metadata),
                readTimeoutMs = 12_000
            )
            if (!set) return@withContext false
            // LG answers Play only after it has opened the stream: measured over
            // 8s on an OLED65BXPVA, so a slow answer is not a refusal.
            soap(
                device.controlUrl, DlnaSoap.PLAY_ACTION, DlnaSoap.playBody(),
                readTimeoutMs = 20_000, lenientOnTimeout = true
            )
        }

    private const val RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"
    private val VOLUME = Regex("<CurrentVolume>(\\d+)</CurrentVolume>", RegexOption.IGNORE_CASE)

    /**
     * A cast lands on whatever the set's volume is, and a viewer reports "the
     * picture works but there is no sound" when it is zero. Read the level and
     * only ever *raise* a silent set: never lower or change a set that is
     * already audible.
     */
    suspend fun ensureAudible(device: DlnaDevice): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (device.renderingUrl.isBlank()) return@withContext false
            val volume = soapRead(
                device.renderingUrl, RENDERING + "#GetVolume",
                DlnaSoap.renderingBody("GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
            )
            val level = VOLUME.find(volume)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext false
            if (level > 0) return@withContext true
            soapRead(
                device.renderingUrl, RENDERING + "#SetMute",
                DlnaSoap.renderingBody("SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>0</DesiredMute>")
            )
            soapRead(
                device.renderingUrl, RENDERING + "#SetVolume",
                DlnaSoap.renderingBody("SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>15</DesiredVolume>")
            )
            true
        }

    suspend fun stop(device: DlnaDevice): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (device.controlUrl.isBlank()) return@withContext false
            soap(device.controlUrl, DlnaSoap.STOP_ACTION, DlnaSoap.stopBody())
        }

    /** DIDL-Lite metadata so the TV shows a title instead of a raw URL. */
    fun didl(title: String, url: String): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        append("<dc:title>${DlnaSoap.escape(title)}</dc:title>")
        append("<upnp:class>object.item.videoItem</upnp:class>")
        append("<res protocolInfo=\"http-get:*:video/mp2t:*\">${DlnaSoap.escape(url)}</res>")
        append("</item></DIDL-Lite>")
    }

    private fun soapRead(
        controlUrl: String,
        action: String,
        body: String,
        readTimeoutMs: Int = 6000
    ): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$action\"")
                setRequestProperty("User-Agent", Http.DEFAULT_UA)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
        } catch (t: Throwable) {
            Logx.w("DLNA_READ ${action.substringAfter('#')} ${t.message}")
            ""
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun soap(
        controlUrl: String,
        action: String,
        body: String,
        readTimeoutMs: Int = 8000,
        lenientOnTimeout: Boolean = false
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$action\"")
                setRequestProperty("User-Agent", Http.DEFAULT_UA)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val failed = DlnaSoap.failed(code, text)
            Logx.i("DLNA ${action.substringAfter('#')} code=$code failed=$failed")
            !failed
        } catch (t: java.net.SocketTimeoutException) {
            // The TV acted on it; it just did not answer in time.
            Logx.i("DLNA ${action.substringAfter('#')} timed-out-but-sent")
            lenientOnTimeout
        } catch (t: Throwable) {
            Logx.w("DLNA_CALL_FAILED ${action.substringAfter('#')}: ${t.message}")
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
