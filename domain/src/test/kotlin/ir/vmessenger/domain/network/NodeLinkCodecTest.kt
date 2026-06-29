package ir.vmessenger.domain.network

import ir.vmessenger.domain.model.NetworkNodeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeLinkCodecTest {
    @Test
    fun roundTripsRelayLinkWithSchemeInAddress() {
        val address = "wss://relay.example.com/relay"
        val link = NodeLinkCodec.encode(NetworkNodeRole.RELAY, address)
        assertEquals("vmnode:relay:wss://relay.example.com/relay", link)

        val decoded = NodeLinkCodec.decode(link)
        assertEquals(NetworkNodeRole.RELAY, decoded?.role)
        assertEquals(address, decoded?.address)
    }

    @Test
    fun decodesBootstrapAndDhtAlias() {
        assertEquals(NetworkNodeRole.BOOTSTRAP, NodeLinkCodec.decode("vmnode:bootstrap:host:46555")?.role)
        assertEquals(NetworkNodeRole.BOOTSTRAP, NodeLinkCodec.decode("vmnode:dht:host:46555")?.role)
        assertEquals("host:46555", NodeLinkCodec.decode("vmnode:bootstrap:host:46555")?.address)
    }

    @Test
    fun trimsWhitespaceAndIsCaseInsensitiveOnScheme() {
        val decoded = NodeLinkCodec.decode("  VMNODE:relay:wss://a/b  ")
        assertEquals(NetworkNodeRole.RELAY, decoded?.role)
        assertEquals("wss://a/b", decoded?.address)
    }

    @Test
    fun returnsNullForInvalidInput() {
        assertNull(NodeLinkCodec.decode("wss://relay.example/relay"))
        assertNull(NodeLinkCodec.decode("vmnode:"))
        assertNull(NodeLinkCodec.decode("vmnode:relay:"))
        assertNull(NodeLinkCodec.decode("vmnode:bogus:addr"))
        assertNull(NodeLinkCodec.decode(""))
    }
}
