package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointResolveServiceExpandTest {
    @After
    fun tearDown() {
        P2PConfig.resetToDefaults()
        NetworkConfig.relayAddress = ""
    }

    @Test
    fun tcpEndpointsAreNeverMirroredAsUdp() = runTest {
        P2PConfig.resetToDefaults()
        P2PConfig.peerCacheEnabled = true
        P2PConfig.natTraversalEnabled = true
        val endpoints = listOf(Endpoint(TransportIds.INTERNET, "203.0.113.1:48555"))
        val service = EndpointResolveService(
            peerEndpointCache = FakeCache(endpoints),
            discoveryManager = DiscoveryManager(emptySet()),
        )
        val result = service.resolve(ByteArray(32)) as AppResult.Success
        assertEquals(endpoints, result.data.endpoints)
        assertTrue(result.data.fromPeerCache)
        assertTrue(result.data.endpoints.none { it.transport == TransportIds.UDP })
    }

    @Test
    fun publishedUdpEndpointIsPassedThroughUnchanged() = runTest {
        P2PConfig.resetToDefaults()
        P2PConfig.peerCacheEnabled = true
        val endpoints = listOf(
            Endpoint(TransportIds.INTERNET, "203.0.113.1:48555"),
            Endpoint(TransportIds.UDP, "203.0.113.1:48556"),
        )
        val service = EndpointResolveService(FakeCache(endpoints), DiscoveryManager(emptySet()))
        val result = service.resolve(ByteArray(32)) as AppResult.Success
        assertEquals(endpoints, result.data.endpoints)
    }

    @Test
    fun emptyLookupFallsBackToOurOwnRelay() = runTest {
        P2PConfig.resetToDefaults()
        NetworkConfig.relayAddress = OWN_RELAY
        val service = EndpointResolveService(FakeCache(null), DiscoveryManager(emptySet()))
        val result = service.resolve(ByteArray(32)) as AppResult.Success
        assertEquals(1, result.data.endpoints.size)
        assertEquals(OWN_RELAY, result.data.endpoints.first().address)
        assertEquals(TransportIds.RELAY, result.data.endpoints.first().transport)
    }

    @Test
    fun discoveryErrorStillFallsBackToOurOwnRelay() = runTest {
        P2PConfig.resetToDefaults()
        NetworkConfig.relayAddress = OWN_RELAY
        val service = EndpointResolveService(FakeCache(null), DiscoveryManager(setOf(FailingProvider)))
        val result = service.resolve(ByteArray(32)) as AppResult.Success
        assertTrue(result.data.discoveryFailed)
        assertFalse(result.data.fromPeerCache)
        assertEquals(1, result.data.endpoints.size)
        assertEquals(OWN_RELAY, result.data.endpoints.first().address)
        assertEquals(TransportIds.RELAY, result.data.endpoints.first().transport)
    }

    @Test
    fun withNoRelaySwitchedOnThereIsNothingToFallBackOn() = runTest {
        P2PConfig.resetToDefaults()
        val service = EndpointResolveService(FakeCache(null), DiscoveryManager(emptySet()))
        val result = service.resolve(ByteArray(32)) as AppResult.Success
        assertTrue("the app has no relay of its own", result.data.endpoints.isEmpty())
    }

    @Test
    fun successfulLookupIsNotFlaggedAsDiscoveryFailure() = runTest {
        P2PConfig.resetToDefaults()
        val service = EndpointResolveService(FakeCache(null), DiscoveryManager(emptySet()))
        val result = service.resolve(ByteArray(32)) as AppResult.Success
        assertFalse(result.data.discoveryFailed)
    }

    /** Stands in for the DHT provider when the network is unreachable ("Not bootstrapped"). */
    private object FailingProvider : DiscoveryProvider {
        override val id = DiscoveryProviderId("failing")

        override suspend fun announce(
            self: DiscoveryIdentity,
            endpoints: List<Endpoint>,
            ed25519PrivateKey: ByteArray,
        ): AppResult<Unit> = AppResult.Error(AppError.Network("Not bootstrapped"))

        override suspend fun resolve(identityHash: ByteArray): AppResult<List<Endpoint>> =
            AppResult.Error(AppError.Network("Not bootstrapped"))
    }

    private class FakeCache(private val endpoints: List<Endpoint>?) : PeerEndpointCache {
        override suspend fun lookup(identityHash: ByteArray) = endpoints
        override suspend fun store(record: EndpointRecord) = Unit
        override suspend fun evict(identityHash: ByteArray) = Unit
    }

    private companion object {
        /** A relay the person added: the app has none of its own. */
        const val OWN_RELAY = "wss://node.example.org/relay"
    }
}
