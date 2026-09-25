package ir.vmessenger.core.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeUrlTest {
    private val pinA = SpkiPinTest.FIXED_PIN
    private val pinB = "B".repeat(42) + "A"

    @Test
    fun `an unpinned URL reads back as it was written`() {
        val url = NodeUrl.parse("wss://relay.vmessenger.ir/relay")!!
        assertEquals("wss", url.scheme)
        assertEquals("relay.vmessenger.ir", url.host)
        assertEquals(-1, url.port)
        assertEquals("/relay", url.path)
        assertFalse(url.isPinned)
        assertEquals("wss://relay.vmessenger.ir/relay", url.dialUrl)
        assertEquals("wss://relay.vmessenger.ir/relay", url.canonical)
        assertEquals("wss://relay.vmessenger.ir:443/relay", url.locationKey)
    }

    @Test
    fun `the pin is kept off the dialled URL and in the canonical one`() {
        val url = NodeUrl.parse("wss://203.0.113.10/dht#pin-sha256=$pinA")!!
        assertTrue(url.isPinned)
        assertEquals(listOf(pinA), url.pins.map { it.text })
        assertEquals("wss://203.0.113.10/dht", url.dialUrl)
        assertEquals("wss://203.0.113.10/dht", url.displayText)
        assertEquals("wss://203.0.113.10/dht#pin-sha256=$pinA", url.canonical)
    }

    @Test
    fun `canonical spelling lowercases, drops the default port and sorts pins`() {
        val a = NodeUrl.parse("WSS://Relay.Example:443/relay#pin-sha256=$pinB,$pinA")!!
        val b = NodeUrl.parse("wss://relay.example/relay#pin-sha256=$pinA,$pinB")!!
        assertEquals(b.canonical, a.canonical)
        assertEquals(a, b)
        assertEquals("wss://relay.example:443/relay", a.locationKey)
    }

    @Test
    fun `same location with another key is the same place, not the same address`() {
        val a = NodeUrl.parse("wss://203.0.113.10/relay#pin-sha256=$pinA")!!
        val b = NodeUrl.parse("wss://203.0.113.10:443/relay#pin-sha256=$pinB")!!
        val unpinned = NodeUrl.parse("wss://203.0.113.10/relay")!!
        assertEquals(a.locationKey, b.locationKey)
        assertEquals(a.locationKey, unpinned.locationKey)
        assertNotEquals(a.canonical, b.canonical)
        assertNotEquals(NodeUrl.parse("wss://203.0.113.10:8443/relay")!!.locationKey, a.locationKey)
    }

    @Test
    fun `IPv6 hosts are bracketed in URLs and bare in host`() {
        val url = NodeUrl.parse("wss://[2001:DB8::10]:8443/relay#pin-sha256=$pinA")!!
        assertEquals("2001:db8::10", url.host)
        assertEquals(8443, url.port)
        assertEquals("wss://[2001:db8::10]:8443/relay", url.dialUrl)
        assertEquals("wss://[2001:db8::10]:8443/relay", NodeUrl.build("2001:db8::10", 8443, "/relay"))
    }

    @Test
    fun `bad pin specs are refused, not ignored`() {
        assertNull(NodeUrl.parse("wss://h/relay#pin-sha256="))
        assertNull(NodeUrl.parse("wss://h/relay#pin-sha256=short"))
        assertNull(NodeUrl.parse("wss://h/relay#sha256=$pinA"))
        assertNull(NodeUrl.parse("wss://h/relay#anything"))
        assertNull(NodeUrl.parse("wss://h/relay#pin-sha256=$pinA,$pinA,$pinA,$pinA,$pinA"))
        assertNull(NodeUrl.parse("ws://10.0.2.2/relay#pin-sha256=$pinA"))
        assertTrue(NodeUrl.parse("wss://h/relay#pin-sha256=$pinA,$pinA,$pinA,$pinA")!!.pins.size == 1)
    }

    @Test
    fun `not node URLs at all`() {
        assertNull(NodeUrl.parse("https://h/relay"))
        assertNull(NodeUrl.parse("wss:///relay"))
        assertNull(NodeUrl.parse("wss://user@h/relay"))
        assertNull(NodeUrl.parse("h:8443"))
        assertNull(NodeUrl.parse("wss://bad host/relay"))
    }

    @Test
    fun `build writes what parse reads`() {
        val pins = listOf(SpkiPin.parse(pinB)!!, SpkiPin.parse(pinA)!!)
        val text = NodeUrl.build("203.0.113.10", 443, "/relay", pins)
        assertEquals("wss://203.0.113.10/relay#pin-sha256=$pinA,$pinB", text)
        assertEquals(text, NodeUrl.parse(text)!!.canonical)
        assertEquals("wss://node.example:8443/dht", NodeUrl.build("Node.Example", 8443, "/dht"))
    }
}
