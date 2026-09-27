package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.data.activity.testActivityLogger
import ir.vmessenger.domain.model.DiscoveryStatus
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.repository.DiscoveryRepository
import ir.vmessenger.domain.usecase.discovery.PublishNetworkEndpointsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The relay the record names is the relay the listener uses: both come from [RelayDirectoryImpl]. */
class RelayPublishAlignmentTest {
    private val repo = NetworkNodeRepository(FakeBootstrapNodeDao(), FakeRelayNodeDao(), testActivityLogger()) {
        NodeAddressPolicy.RELEASE
    }
    private val directory = RelayDirectoryImpl(repo)
    private val discovery = RecordingDiscovery()
    private val publish = PublishNetworkEndpointsUseCase(discovery)

    @After
    fun reset() {
        NetworkConfig.relayAddress = ""
        NetworkConfig.rankedRelayUrls = emptyList()
    }

    @Test
    fun publishedRelayEndpointMatchesSelectedRelay() = runTest {
        repo.addNode("wss://node-a.example/relay", NetworkNodeRole.RELAY)
        val selected = directory.activeRelay()!!

        assertTrue(publish(relayUrl = selected.url) is AppResult.Success)

        val relay = discovery.published.single().single { it.transport == TransportIds.RELAY }
        assertEquals(selected.url, relay.address)
        assertEquals(selected.url, NetworkConfig.relayAddress)
    }

    @Test
    fun withNoRelayNothingIsPublished() = runTest {
        val selected = directory.activeRelay()
        assertNull(selected)

        assertTrue(publish(relayUrl = selected?.url) is AppResult.Error)
        assertTrue("no record names a relay the app does not have", discovery.published.isEmpty())
    }

    private class RecordingDiscovery : DiscoveryRepository {
        val published = mutableListOf<List<Endpoint>>()

        override fun observeStatus(): Flow<DiscoveryStatus> = emptyFlow()

        override suspend fun joinNetwork(): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun publishEndpoint(endpoint: Endpoint): AppResult<Unit> = publishEndpoints(listOf(endpoint))

        override suspend fun publishEndpoints(endpoints: List<Endpoint>): AppResult<Unit> {
            published += endpoints
            return AppResult.Success(Unit)
        }

        override suspend fun getPublishedEndpoint(): String? = null
    }
}
