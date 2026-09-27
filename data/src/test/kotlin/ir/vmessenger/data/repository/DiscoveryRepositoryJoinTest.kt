package ir.vmessenger.data.repository

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.ListenerAlert
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.data.activity.testActivityLogger
import ir.vmessenger.data.network.DatabaseBootstrapProvider
import ir.vmessenger.data.network.FakeBootstrapNodeDao
import ir.vmessenger.data.network.FakeRelayNodeDao
import ir.vmessenger.data.network.NetworkNodeRepository
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.network.bootstrap.BootstrapManager
import ir.vmessenger.network.bootstrap.BootstrapNode
import ir.vmessenger.network.bootstrap.DevBootstrapProvider
import ir.vmessenger.network.dht.Dht
import ir.vmessenger.network.dht.EndpointRecordSigner
import ir.vmessenger.network.discovery.DhtDiscoveryProvider
import ir.vmessenger.network.discovery.PeerEndpointCache
import ir.vmessenger.network.discovery.PublishSequenceStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Joining the DHT uses only the nodes the person added: the app seeds none of its own. */
class DiscoveryRepositoryJoinTest {
    private val crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val bootstrapDao = FakeBootstrapNodeDao()
    private val relayDao = FakeRelayNodeDao()
    private val nodes = NetworkNodeRepository(bootstrapDao, relayDao, testActivityLogger()) {
        NodeAddressPolicy.RELEASE
    }
    private val dht = RecordingDht()
    private val repository = DiscoveryRepositoryImpl(
        bootstrapManager = BootstrapManager(setOf(DevBootstrapProvider(), DatabaseBootstrapProvider(nodes))),
        dht = dht,
        dhtDiscoveryProvider = DhtDiscoveryProvider(dht, EndpointRecordSigner(crypto), NoSequences, NoCache),
        identityRepository = FakeIdentityRepository(crypto),
        networkNodeRepository = nodes,
    )

    @After
    fun reset() {
        NetworkPathTracker.clear()
    }

    @Test
    fun `a join with no node seeds none and says the bootstrap node is missing`() = runTest {
        val result = repository.joinNetwork()

        assertEquals(AppError.NoBootstrapNode, (result as AppResult.Error).error)
        assertTrue(bootstrapDao.getAll().isEmpty())
        assertTrue(relayDao.getAll().isEmpty())
        assertTrue(dht.bootstrapped.isEmpty())
        assertEquals(ListenerAlert.NO_BOOTSTRAP, NetworkPathTracker.listenerAlert.value)
    }

    @Test
    fun `a join through the person's node clears that`() = runTest {
        NetworkPathTracker.reportBootstrapMissing(true)
        nodes.addNode("wss://node.example.org/dht", NetworkNodeRole.BOOTSTRAP)

        assertTrue(repository.joinNetwork() is AppResult.Success)
        assertEquals(listOf("wss://node.example.org/dht"), dht.bootstrapped)
        assertEquals(ListenerAlert.NONE, NetworkPathTracker.listenerAlert.value)
    }

    private class RecordingDht : Dht {
        val bootstrapped = mutableListOf<String>()

        override suspend fun bootstrap(nodes: List<BootstrapNode>): AppResult<List<BootstrapNode>> {
            bootstrapped += nodes.map { it.address }
            return AppResult.Success(nodes)
        }

        override suspend fun publish(record: EndpointRecord): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun lookup(identityHash: ByteArray): AppResult<EndpointRecord?> = AppResult.Success(null)

        override fun knownNodeAddresses(): Set<String> = emptySet()
    }

    private object NoSequences : PublishSequenceStore {
        override suspend fun nextSequence(identityHash: ByteArray): Long = 1

        override suspend fun bumpToAtLeast(identityHash: ByteArray, minimum: Long): Long = minimum
    }

    private object NoCache : PeerEndpointCache {
        override suspend fun lookup(identityHash: ByteArray): List<Endpoint>? = null

        override suspend fun store(record: EndpointRecord) = Unit

        override suspend fun evict(identityHash: ByteArray) = Unit
    }
}
