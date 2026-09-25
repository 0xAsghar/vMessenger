package ir.vmessenger.core.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeAddressPolicyTest {
    private val release = NodeAddressPolicy(allowInsecureLocal = false)
    private val debug = NodeAddressPolicy(allowInsecureLocal = true)

    @Test
    fun releaseAcceptsOnlySecureWebSocketWithHost() {
        assertTrue(release.isRelayAllowed("wss://relay.vmessenger.ir/relay"))
        assertTrue(release.isRelayAllowed("WSS://relay.vmessenger.ir:8443"))
        assertTrue(release.isBootstrapAllowed("wss://relay.vmessenger.ir/dht"))
        assertEquals(NodeAddressRejection.INSECURE_NOT_LOCAL, release.checkRelay("ws://relay.example/relay"))
        assertEquals(NodeAddressRejection.INSECURE_NOT_LOCAL, release.checkRelay("ws://10.0.2.2:8443/relay"))
        assertEquals(NodeAddressRejection.INSECURE_NOT_LOCAL, release.checkBootstrap("10.0.2.2:46555"))
        assertEquals(NodeAddressRejection.INSECURE_NOT_LOCAL, release.checkBootstrap("node.example:46555"))
        assertEquals(NodeAddressRejection.MALFORMED, release.checkRelay("https://relay.example/relay"))
        assertEquals(NodeAddressRejection.MALFORMED, release.checkRelay("wss://"))
        assertEquals(NodeAddressRejection.MALFORMED, release.checkRelay("wss:///relay"))
        assertEquals(NodeAddressRejection.MALFORMED, release.checkRelay("relay.example"))
        assertEquals(NodeAddressRejection.MALFORMED, release.checkRelay("wss://user:pw@relay.example/relay"))
        assertEquals(NodeAddressRejection.MALFORMED, release.checkRelay("wss://bad host/relay"))
        assertEquals(NodeAddressRejection.BLANK, release.checkRelay("   "))
        assertEquals(NodeAddressRejection.BLANK, release.checkBootstrap(""))
    }

    @Test
    fun debugAllowsInsecureOnlyForLocalHosts() {
        assertNull(debug.checkRelay("ws://10.0.2.2:8443/relay"))
        assertNull(debug.checkRelay("ws://localhost:8443/relay"))
        assertNull(debug.checkRelay("ws://127.0.0.1/relay"))
        assertNull(debug.checkRelay("ws://192.168.1.20:8443/relay"))
        assertNull(debug.checkRelay("ws://172.16.0.9:8443/relay"))
        assertNull(debug.checkBootstrap("10.0.2.2:46555"))
        assertNull(debug.checkBootstrap("ws://10.0.2.2:46555"))
        assertNull(debug.checkBootstrap("wss://relay.vmessenger.ir/dht"))
        assertEquals(NodeAddressRejection.INSECURE_NOT_LOCAL, debug.checkRelay("ws://relay.example/relay"))
        assertEquals(NodeAddressRejection.INSECURE_NOT_LOCAL, debug.checkRelay("ws://8.8.8.8/relay"))
        assertEquals(NodeAddressRejection.INSECURE_NOT_LOCAL, debug.checkRelay("ws://172.32.0.1/relay"))
        assertEquals(NodeAddressRejection.INSECURE_NOT_LOCAL, debug.checkBootstrap("node.example:46555"))
        assertEquals(NodeAddressRejection.MALFORMED, debug.checkBootstrap("10.0.2.2:0"))
        assertEquals(NodeAddressRejection.MALFORMED, debug.checkBootstrap("10.0.2.2:70000"))
    }

    @Test
    fun localHostDetection() {
        assertTrue(NodeAddressPolicy.isLocalHost("10.0.2.2"))
        assertTrue(NodeAddressPolicy.isLocalHost("LOCALHOST"))
        assertTrue(NodeAddressPolicy.isLocalHost("127.0.0.1"))
        assertTrue(NodeAddressPolicy.isLocalHost("10.20.30.40"))
        assertTrue(NodeAddressPolicy.isLocalHost("172.31.255.255"))
        assertTrue(NodeAddressPolicy.isLocalHost("192.168.0.1"))
        assertFalse(NodeAddressPolicy.isLocalHost("172.15.0.1"))
        assertFalse(NodeAddressPolicy.isLocalHost("192.169.0.1"))
        assertFalse(NodeAddressPolicy.isLocalHost("11.0.0.1"))
        assertFalse(NodeAddressPolicy.isLocalHost("10.0.2"))
        assertFalse(NodeAddressPolicy.isLocalHost("10.0.2.256"))
        assertFalse(NodeAddressPolicy.isLocalHost("relay.vmessenger.ir"))
        assertFalse(NodeAddressPolicy.isLocalHost("localhost.example"))
    }

    @Test
    fun processWideDefaultIsRelease() {
        assertFalse(NodeAddressPolicy.RELEASE.allowInsecureLocal)
    }

    @Test
    fun pinnedAddressesAreAcceptedAndBadPinsNamed() {
        val pin = SpkiPinTest.FIXED_PIN
        assertTrue(release.isRelayAllowed("wss://203.0.113.10/relay#pin-sha256=$pin"))
        assertTrue(release.isBootstrapAllowed("wss://203.0.113.10:8443/dht#pin-sha256=$pin"))
        assertEquals(NodeAddressRejection.MALFORMED_PIN, release.checkRelay("wss://203.0.113.10/relay#pin-sha256=nope"))
        assertEquals(NodeAddressRejection.MALFORMED_PIN, release.checkRelay("wss://203.0.113.10/relay#note"))
        assertEquals(NodeAddressRejection.MALFORMED_PIN, debug.checkRelay("ws://10.0.2.2/relay#pin-sha256=$pin"))
        assertEquals(NodeAddressRejection.MALFORMED, release.checkRelay("https://h/relay#pin-sha256=$pin"))
    }
}
