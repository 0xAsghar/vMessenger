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

    @Test
    fun keepsAPinnedAddressWhole() {
        val address = "wss://203.0.113.10/relay#pin-sha256=601FQOh6ckV1-Qbw-9F3cGprfojLs5_j4Hkn7DPKFfc"
        val link = NodeLinkCodec.encode(NetworkNodeRole.RELAY, address)
        assertEquals("vmnode:relay:$address", link)
        assertEquals(address, NodeLinkCodec.decode(link)?.address)
    }

    @Test
    fun aNodeAddressNamesItsOtherHalfOnTheSameHostPortAndPin() {
        val pin = PIN
        assertEquals(
            NodeLink(NetworkNodeRole.BOOTSTRAP, "wss://node.example.org/dht"),
            NodeLinkCodec.companionOf(NetworkNodeRole.RELAY, "wss://node.example.org/relay"),
        )
        assertEquals(
            NodeLink(NetworkNodeRole.RELAY, "wss://203.0.113.10:8443/relay#pin-sha256=$pin"),
            NodeLinkCodec.companionOf(NetworkNodeRole.BOOTSTRAP, "wss://203.0.113.10:8443/dht#pin-sha256=$pin"),
        )
    }

    @Test
    fun anAddressOnAnotherPathHasNoKnownOtherHalf() {
        assertNull(NodeLinkCodec.companionOf(NetworkNodeRole.RELAY, "wss://node.example.org/custom"))
        assertNull(NodeLinkCodec.companionOf(NetworkNodeRole.RELAY, "wss://node.example.org/dht"))
        assertNull(NodeLinkCodec.companionOf(NetworkNodeRole.RELAY, "wss://node.example.org/relay?x=1"))
        assertNull(NodeLinkCodec.companionOf(NetworkNodeRole.BOOTSTRAP, "10.0.2.2:46555"))
    }

    @Test
    fun aPlainAddressNamesItsRoleByItsPath() {
        assertEquals(NetworkNodeRole.BOOTSTRAP, NodeLinkCodec.roleOfPath("wss://node.example.org/dht"))
        assertEquals(NetworkNodeRole.RELAY, NodeLinkCodec.roleOfPath("wss://node.example.org/relay#pin-sha256=$PIN"))
        assertNull(NodeLinkCodec.roleOfPath("wss://node.example.org/custom"))
        // A link's own role decides, even when its address says otherwise.
        assertNull(NodeLinkCodec.roleOfPath("vmnode:relay:wss://node.example.org/dht"))
        assertNull(NodeLinkCodec.roleOfPath("not an address"))
    }

    private companion object {
        const val PIN = "601FQOh6ckV1-Qbw-9F3cGprfojLs5_j4Hkn7DPKFfc"
    }
}
