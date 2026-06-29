package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.TransportIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointResolveServiceExpandTest {
    @Test
    fun natTraversalAddsUdpMirrorWhenEnabled() = runTest {
        P2PConfig.resetToDefaults()
        P2PConfig.natTraversalEnabled = true
        val endpoints = listOf(Endpoint(TransportIds.INTERNET, "203.0.113.1:48555"))
        val service = EndpointResolveService(
            peerEndpointCache = object : PeerEndpointCache {
                override suspend fun lookup(identityHash: ByteArray) = endpoints
                override suspend fun store(record: ir.vmessenger.core.proto.dht.v1.EndpointRecord) = Unit
            },
            discoveryManager = DiscoveryManager(emptySet()),
        )
        val result = service.resolve(ByteArray(32)) as ir.vmessenger.core.common.AppResult.Success
        assertTrue(result.data.endpoints.any { it.transport == TransportIds.UDP })
    }

    @Test
    fun emptyLookupFallsBackToDefaultRelay() = runTest {
        P2PConfig.resetToDefaults()
        val service = EndpointResolveService(
            peerEndpointCache = object : PeerEndpointCache {
                override suspend fun lookup(identityHash: ByteArray) = null
                override suspend fun store(record: ir.vmessenger.core.proto.dht.v1.EndpointRecord) = Unit
            },
            discoveryManager = DiscoveryManager(emptySet()),
        )
        val result = service.resolve(ByteArray(32)) as AppResult.Success
        assertEquals(1, result.data.endpoints.size)
        assertEquals(NetworkConfig.DEFAULT_RELAY_URL, result.data.endpoints.first().address)
        assertEquals(TransportIds.RELAY, result.data.endpoints.first().transport)
    }
}
