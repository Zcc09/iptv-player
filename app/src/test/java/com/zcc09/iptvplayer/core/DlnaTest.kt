package com.zcc09.iptvplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaTest {

    private val ssdpResponse = "HTTP/1.1 200 OK\r\n" +
        "CACHE-CONTROL: max-age=1800\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "USN: uuid:abcd::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "LOCATION: http://192.168.0.50:9197/dmr\r\n" +
        "SERVER: Linux/4.1 UPnP/1.0 Samsung\r\n\r\n"

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living Room TV</friendlyName>
            <modelName>OLED55C1</modelName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/upnp/control/AVTransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun readsTheLocationFromAnSsdpResponse() {
        assertEquals("http://192.168.0.50:9197/dmr", Ssdp.locationOf(ssdpResponse))
    }

    @Test
    fun ignoresNonOkOrJunkResponses() {
        assertNull(Ssdp.locationOf("HTTP/1.1 404 Not Found\r\nLOCATION: http://x/y\r\n\r\n"))
        assertNull(Ssdp.locationOf("garbage"))
    }

    @Test
    fun searchMessageIsAValidMSearch() {
        val msg = Ssdp.searchMessage()
        assertTrue(msg.startsWith("M-SEARCH * HTTP/1.1"))
        assertTrue(msg.contains("MAN: \"ssdp:discover\""))
        assertTrue(msg.contains(Ssdp.SEARCH_TARGET))
        assertTrue(msg.endsWith("\r\n\r\n"))
    }

    @Test
    fun findsTheAvTransportControlUrlNotRenderingControl() {
        val control = DlnaDescription.controlUrl(description, "http://192.168.0.50:9197/dmr")
        assertEquals("http://192.168.0.50:9197/upnp/control/AVTransport1", control)
    }

    @Test
    fun readsTheFriendlyNameAndModel() {
        assertEquals("Living Room TV", DlnaDescription.friendlyName(description))
        assertEquals("OLED55C1", DlnaDescription.modelName(description))
    }

    @Test
    fun aDescriptionWithoutAvTransportIsRejected() {
        val noAv = description.replace("AVTransport", "ConnectionManager")
        assertNull(DlnaDescription.controlUrl(noAv, "http://192.168.0.50:9197/dmr"))
    }

    @Test
    fun absoluteControlUrlsAreLeftAlone() {
        val abs = description.replace(
            "/upnp/control/AVTransport1",
            "http://192.168.0.50:1234/ctl"
        )
        assertEquals(
            "http://192.168.0.50:1234/ctl",
            DlnaDescription.controlUrl(abs, "http://192.168.0.50:9197/dmr")
        )
    }

    @Test
    fun setUriBodyEscapesTheUrlIntoValidXml() {
        val body = DlnaSoap.setUriBody("http://192.168.0.172:8080/a.ts?a=1&b=2", "<DIDL/>")
        assertTrue(body.contains("SetAVTransportURI"))
        assertTrue(body.contains("a=1&amp;b=2"))
        assertFalse(body.contains("a=1&b=2"))
        assertTrue(body.contains("&lt;DIDL/&gt;"))
    }

    @Test
    fun successAndFailureAreDistinguished() {
        assertFalse(DlnaSoap.failed(200, "<ok/>"))
        // UPnP reports an error as 500 + errorCode
        assertTrue(DlnaSoap.failed(500, "<s:Fault><errorCode>501</errorCode></s:Fault>"))
        assertTrue(DlnaSoap.failed(500, ""))
        assertFalse(DlnaSoap.failed(500, "<errorCode>0</errorCode>"))
    }

    @Test
    fun didlMetadataCarriesTheTitleAndUrl() {
        val didl = Dlna.didl("BBC One", "http://host/a.ts")
        assertTrue(didl.contains("<dc:title>BBC One</dc:title>"))
        assertTrue(didl.contains("http://host/a.ts"))
        assertTrue(didl.contains("object.item.videoItem"))
    }

    @Test
    fun deviceIdFallsBackToTheLocation() {
        val d = DlnaDevice(name = "TV", location = "http://x/desc", controlUrl = "")
        assertEquals("http://x/desc", d.id)
        assertNotNull(DlnaDevice("TV", "http://x/desc", "http://x/ctl").id)
    }
}
