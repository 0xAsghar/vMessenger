package ir.vmessenger.network.dht

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import ir.vmessenger.core.proto.dht.v1.PingRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class EmbeddedDhtServiceTest {
    private val nodeId = ByteArray(32) { (it + 1).toByte() }
    private val service = EmbeddedDhtService(
        recordStore = EmbeddedDhtRecordStore(
            verifier = EndpointRecordVerifier(LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))),
            dhtRecordDao = FakeDhtRecordDao(),
            rpcClient = FakeDhtRpcSender(),
            nodeIdProvider = DhtNodeIdProvider { nodeId },
            storeRateLimiter = StoreRateLimiter(),
        ),
        dhtPolicy = object : DhtParticipationPolicy {
            override fun shouldParticipate() = true
            override fun shouldAdvertise(host: String) = true
        },
    )

    @Before
    fun setUp() {
        P2PConfig.dhtParticipationEnabled = true
    }

    @After
    fun tearDown() {
        service.stop()
        P2PConfig.resetToDefaults()
    }

    private fun awaitPort(): Int {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            service.listeningPort()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("listener did not start")
    }

    @Test
    fun stopClosesSocket() {
        service.start(dhtPort = 0, advertisedHost = "127.0.0.1")
        val port = awaitPort()
        assertTrue(service.isRunning)
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }

        service.stop()

        assertFalse(service.isRunning)
        assertNull(service.listeningPort())
        val refused = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
        }.isFailure
        assertTrue("port must be closed after stop()", refused)
        // Idempotent: a second stop is a no-op, and the service can be started again.
        service.stop()
        service.start(dhtPort = 0, advertisedHost = "127.0.0.1")
        assertNotNull(awaitPort())
    }

    @Test
    fun pingAnswersWithProvidedNodeId() {
        service.start(dhtPort = 0, advertisedHost = "127.0.0.1")
        val port = awaitPort()
        val response = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            val out = BufferedOutputStream(socket.getOutputStream())
            val request = DhtRpcRequest.newBuilder().setPing(PingRequest.newBuilder()).build()
            LengthPrefixedFrames.writeFrame(out, request.toByteArray())
            val bytes = LengthPrefixedFrames.readFrame(BufferedInputStream(socket.getInputStream()))
            DhtRpcResponse.parseFrom(requireNotNull(bytes))
        }
        assertTrue(response.hasPing())
        assertArrayEquals(nodeId, response.ping.nodeId.toByteArray())
    }

    @Test
    fun startIsSkippedWhenParticipationDisabled() {
        P2PConfig.dhtParticipationEnabled = false
        service.start(dhtPort = 0, advertisedHost = "127.0.0.1")
        assertFalse(service.isRunning)
        assertNull(service.listeningPort())
    }

    private companion object {
        const val START_TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
        const val CONNECT_TIMEOUT_MS = 2_000
    }
}
