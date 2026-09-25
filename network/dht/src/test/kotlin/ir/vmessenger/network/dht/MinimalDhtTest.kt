package ir.vmessenger.network.dht

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.network.bootstrap.BootstrapNode
import ir.vmessenger.network.bootstrap.BootstrapProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalDhtTest {
    private val verifier = EndpointRecordVerifier(LazysodiumCryptoEngine(LazySodiumJava(SodiumJava())))

    private fun node(address: String) = BootstrapNode(address = address, source = BootstrapProviderId("test"))

    @Test
    fun knownNodesConcurrentMutation() = runBlocking {
        // Every lookup learns new node addresses while other coroutines iterate the set:
        // with a plain HashSet this raced into ConcurrentModificationException.
        val learned = (1..200).map { "wss://node-$it.example/dht" }
        val dht = MinimalDht(FakeDhtRpcSender(nodes = learned), verifier)
        assertTrue(dht.bootstrap(listOf(node(NetworkConfig.DEFAULT_DHT_URL))) is AppResult.Success)

        withContext(Dispatchers.Default) {
            val writers = (1..8).map {
                async { repeat(20) { assertTrue(dht.lookup(ByteArray(32)) is AppResult.Success) } }
            }
            val readers = (1..8).map {
                async { repeat(200) { dht.knownNodeAddresses().forEach { require(it.isNotBlank()) } } }
            }
            (writers + readers).awaitAll()
        }
        assertEquals(learned.size + 1, dht.knownNodeCount())
    }

    @Test
    fun enabledBootstrapNodeAddressIsAnRpcTarget() = runBlocking {
        val userNode = "192.168.1.20:46555"
        val sender = FakeDhtRpcSender()
        val dht = MinimalDht(sender, verifier)
        assertTrue(dht.bootstrap(listOf(node(userNode))) is AppResult.Success)
        sender.sent.clear()

        val lookup = dht.lookup(ByteArray(32))
        assertTrue(lookup is AppResult.Success)
        assertNull((lookup as AppResult.Success).data)
        assertEquals(listOf(userNode), sender.sent.map { it.first })
    }

    @Test
    fun unknownPeerSuppliedAddressesAreIgnored() = runBlocking {
        val dht = MinimalDht(FakeDhtRpcSender(nodes = listOf("203.0.113.9:1234", "evil.example:80")), verifier)
        assertTrue(dht.bootstrap(listOf(node(NetworkConfig.DEFAULT_DHT_URL))) is AppResult.Success)
        launch { dht.lookup(ByteArray(32)) }.join()
        assertEquals(setOf(NetworkConfig.DEFAULT_DHT_URL), dht.knownNodeAddresses())
    }

    @Test
    fun oneFailingTargetDoesNotAbortPublish() = runBlocking {
        val dead = "wss://dead.example/dht"
        val sender = FakeDhtRpcSender(nodes = listOf(dead), failing = setOf(dead))
        val dht = MinimalDht(sender, verifier)
        assertTrue(dht.bootstrap(listOf(node(NetworkConfig.DEFAULT_DHT_URL))) is AppResult.Success)
        // Learn the unreachable node from a find-value answer, then use it as an RPC target.
        assertTrue(dht.lookup(ByteArray(32)) is AppResult.Success)
        assertEquals(setOf(NetworkConfig.DEFAULT_DHT_URL, dead), dht.knownNodeAddresses())
        sender.sent.clear()

        val record = EndpointRecord.newBuilder().setSequence(1).build()
        assertTrue(dht.publish(record) is AppResult.Success)
        assertEquals(setOf(NetworkConfig.DEFAULT_DHT_URL, dead), sender.sent.map { it.first }.toSet())
        assertTrue(dht.lookup(ByteArray(32)) is AppResult.Success)

        // Repeated failures forget the learned node (once nobody re-advertises it);
        // the bootstrap node is never dropped.
        sender.nodes = emptyList()
        repeat(3) { dht.lookup(ByteArray(32)) }
        assertEquals(setOf(NetworkConfig.DEFAULT_DHT_URL), dht.knownNodeAddresses())
    }

    @Test
    fun allTargetsFailingIsAnError() = runBlocking {
        val sender = FakeDhtRpcSender()
        val dht = MinimalDht(sender, verifier)
        assertTrue(dht.bootstrap(listOf(node(NetworkConfig.DEFAULT_DHT_URL))) is AppResult.Success)
        sender.failing += NetworkConfig.DEFAULT_DHT_URL
        assertTrue(dht.lookup(ByteArray(32)) is AppResult.Error)
        assertTrue(dht.publish(EndpointRecord.newBuilder().setSequence(1).build()) is AppResult.Error)
        // The bootstrap node itself is kept so a later retry can reach it again.
        assertEquals(setOf(NetworkConfig.DEFAULT_DHT_URL), dht.knownNodeAddresses())
    }

    @Test
    fun policyRejectedLearnedAddressesAreNotTargets() = runBlocking {
        val release = NodeAddressPolicy.RELEASE
        assertNull(normalizeDhtRpcAddress("ws://evil.example/dht", policy = release))
        assertEquals("wss://ok.example/dht", normalizeDhtRpcAddress("wss://ok.example/dht", policy = release))
        // A user-enabled bootstrap address was policy-checked when stored and stays a target.
        assertEquals(
            "ws://10.0.2.2:46555",
            normalizeDhtRpcAddress("ws://10.0.2.2:46555", trusted = setOf("ws://10.0.2.2:46555"), policy = release),
        )
        val dht = MinimalDht(FakeDhtRpcSender(nodes = listOf("ws://evil.example/dht")), verifier)
        assertTrue(dht.bootstrap(listOf(node(NetworkConfig.DEFAULT_DHT_URL))) is AppResult.Success)
        assertTrue(dht.lookup(ByteArray(32)) is AppResult.Success)
        assertEquals(setOf(NetworkConfig.DEFAULT_DHT_URL), dht.knownNodeAddresses())
    }

    @Test
    fun normalizeAcceptsAllowlistAndTrustedOnly() {
        assertEquals("wss://x/dht", normalizeDhtRpcAddress("wss://x/dht"))
        assertEquals(NetworkConfig.DEFAULT_DHT_URL, normalizeDhtRpcAddress("${NetworkConfig.RELAY_HOST}:8443"))
        assertEquals(NetworkConfig.DEV_BOOTSTRAP_ADDRESS, normalizeDhtRpcAddress(NetworkConfig.DEV_BOOTSTRAP_ADDRESS))
        assertNull(normalizeDhtRpcAddress("10.1.2.3:46555"))
        // A pinned node keeps its pin, and a malformed pin is not a target.
        val pinned = "wss://203.0.113.10/dht#pin-sha256=601FQOh6ckV1-Qbw-9F3cGprfojLs5_j4Hkn7DPKFfc"
        assertEquals(pinned, normalizeDhtRpcAddress(pinned, policy = NodeAddressPolicy.RELEASE))
        assertNull(normalizeDhtRpcAddress("wss://203.0.113.10/dht#pin-sha256=x", policy = NodeAddressPolicy.RELEASE))
        assertEquals("10.1.2.3:46555", normalizeDhtRpcAddress("10.1.2.3:46555", trusted = setOf("10.1.2.3:46555")))
    }
}
