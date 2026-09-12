package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.RelaySource
import ir.vmessenger.core.common.network.SelectedRelay
import ir.vmessenger.core.common.network.TransportId
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.ChatMessage
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.network.discovery.DiscoveryManager
import ir.vmessenger.network.discovery.EndpointResolveService
import ir.vmessenger.network.discovery.PeerEndpointCache
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.InternetTransport
import ir.vmessenger.network.transport.RelayTransport
import ir.vmessenger.network.transport.Transport
import ir.vmessenger.network.transport.TransportCapabilities
import ir.vmessenger.network.transport.TransportSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-contact session slots: a slow dial to one contact must not delay a send to
 * another, an open session is reused across sends, and a session that closes
 * disappears from its slot (and the slot from the map).
 */
class MessagingServiceConcurrencyTest {
    private lateinit var crypto: CryptoEngine
    private lateinit var alice: PeerIdentity
    private lateinit var bob: PeerIdentity
    private lateinit var carol: PeerIdentity
    private lateinit var transport: SlowPipedTransport
    private lateinit var service: MessagingService

    @Before
    fun setUp() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        val factory = SecureChannelFactory(crypto, SymmetricRatchet(crypto))
        alice = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        bob = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        carol = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        val responders = mapOf(BOB_ENDPOINT to bob, CAROL_ENDPOINT to carol)
        transport = SlowPipedTransport(connectDelayMs = CONNECT_DELAY_MS) { endpoint, server ->
            val responder = checkNotNull(responders[endpoint])
            factory.accept(server, responder, alice).getOrThrow() as ActiveSecureSession
        }
        val relayTransport = RelayTransport()
        P2PConfig.resetToDefaults()
        P2PConfig.peerCacheEnabled = true
        service = MessagingService(
            endpointResolveService = EndpointResolveService(
                PerPeerEndpointCache(
                    mapOf(
                        bob.identityHash.toList() to listOf(BOB_ENDPOINT),
                        carol.identityHash.toList() to listOf(CAROL_ENDPOINT),
                    ),
                ),
                DiscoveryManager(emptySet()),
            ),
            sessionPostHandshakeHandler = SessionPostHandshakeHandler { _, _, _ -> },
            transportSelector = TransportSelector(setOf(transport), relayTransport),
            secureChannelFactory = factory,
            internetTransport = InternetTransport(),
            relayListener = RelayListener(relayTransport, RelayHelloFactory(crypto), FakeRelayDirectory()),
        )
        service.configureInbound(
            selfProvider = { alice },
            resolveInboundPeer = { _, _ -> null },
            contactIdResolver = { null },
        )
    }

    @After
    fun tearDown() {
        runBlocking { service.closeAll() }
        transport.shutdown()
        P2PConfig.resetToDefaults()
    }

    @Test
    fun sendsToDifferentContactsRunInParallel() = runBlocking {
        val started = System.nanoTime()
        val results = listOf(
            async(Dispatchers.Default) { service.send(BOB, alice, bob, envelope("to bob")) },
            async(Dispatchers.Default) { service.send(CAROL, alice, carol, envelope("to carol")) },
        ).awaitAll()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        results.forEach { assertTrue("expected success, got $it", it is AppResult.Success) }
        assertTrue("two 2 s dials must overlap, took $elapsedMs ms", elapsedMs < 3_000)
        assertEquals(2, transport.dials.get())
    }

    @Test
    fun sessionReusedAcrossSends() = runBlocking {
        assertTrue(service.send(BOB, alice, bob, envelope("one")) is AppResult.Success)
        val first = service.outboundSession(BOB)
        assertNotNull(first)
        assertTrue(service.send(BOB, alice, bob, envelope("two")) is AppResult.Success)
        val batch = service.sendBatch(BOB, alice, bob, sequenceOf(envelope("three"), envelope("four")), onSent = {})
        assertTrue("expected success, got $batch", batch is AppResult.Success)
        assertEquals("one dial for four envelopes", 1, transport.dials.get())
        assertTrue("same session object reused", first === service.outboundSession(BOB))
        val received = withTimeout(5_000) { transport.awaitFrames(BOB_ENDPOINT, 4) }
        assertEquals(4, received)
    }

    @Test
    fun sendBatchReportsEachSentIndexInOrder() = runBlocking {
        val sent = CopyOnWriteArrayList<Int>()
        val result = service.sendBatch(
            BOB,
            alice,
            bob,
            (0 until 5).asSequence().map { envelope("chunk-$it") },
            onSent = { sent += it },
        )
        assertTrue("expected success, got $result", result is AppResult.Success)
        assertEquals(listOf(0, 1, 2, 3, 4), sent)
        assertEquals(1, transport.dials.get())
    }

    @Test
    fun closedSessionRemovedFromSlot() = runBlocking {
        assertTrue(service.send(BOB, alice, bob, envelope("hello")) is AppResult.Success)
        assertTrue(service.hasOutboundSlot(BOB))
        val serverSession = withTimeout(5_000) { transport.awaitSession(BOB_ENDPOINT) }
        // The peer drops the connection: the read loop ends, the session is
        // detached and closed, and the slot (nobody else holds it) is gone.
        serverSession.close()
        withTimeout(5_000) {
            while (service.hasOutboundSlot(BOB)) delay(20)
        }
        assertNull(service.outboundSession(BOB))
        assertFalse(service.hasOutboundSlot(BOB))
        // A later send dials afresh instead of writing on the dead session.
        assertTrue(service.send(BOB, alice, bob, envelope("again")) is AppResult.Success)
        assertEquals(2, transport.dials.get())
    }

    @Test
    fun closeSessionsDropsOutboundSession() = runBlocking {
        assertTrue(service.send(BOB, alice, bob, envelope("hello")) is AppResult.Success)
        service.closeSessions(BOB)
        assertNull(service.outboundSession(BOB))
        withTimeout(5_000) {
            while (service.hasOutboundSlot(BOB)) delay(20)
        }
    }

    private fun envelope(text: String): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("id-$text"))
        .setSentAtUnixMs(System.currentTimeMillis())
        .setCounter(1)
        .setChat(ChatMessage.newBuilder().setText(text))
        .build()

    private fun identity(ed: KeyPair, x: KeyPair) = PeerIdentity(
        identityHash = crypto.sha256(ed.publicKey),
        ed25519PublicKey = ed.publicKey,
        x25519StaticPublicKey = x.publicKey,
        ed25519PrivateKey = ed.privateKey,
        x25519StaticPrivateKey = x.privateKey,
    )

    /**
     * INTERNET transport whose every dial takes [connectDelayMs] and is answered
     * in-memory by [responder]; the responder session keeps reading frames so
     * the test can count what arrived per endpoint.
     */
    private class SlowPipedTransport(
        private val connectDelayMs: Long,
        private val responder: suspend (endpoint: Endpoint, server: Connection) -> ActiveSecureSession,
    ) : Transport {
        override val id: TransportId = TransportIds.INTERNET
        override val capabilities = TransportCapabilities(reliable = true, ordered = true, mtu = 65_535)
        val dials = AtomicInteger()
        private val sessions = ConcurrentHashMap<Endpoint, ActiveSecureSession>()
        private val frames = ConcurrentHashMap<Endpoint, AtomicInteger>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        override fun canReach(endpoint: Endpoint): Boolean = endpoint.transport == TransportIds.INTERNET

        override suspend fun connect(endpoint: Endpoint): Result<Connection> {
            dials.incrementAndGet()
            delay(connectDelayMs)
            val (client, server) = pairedConnections()
            scope.launch {
                runCatching {
                    val session = responder(endpoint, server)
                    sessions[endpoint] = session
                    server.read().collect { frames.getOrPut(endpoint) { AtomicInteger() }.incrementAndGet() }
                }
            }
            return Result.success(client)
        }

        override fun listen(port: Int): Flow<Connection> = emptyFlow()

        suspend fun awaitSession(endpoint: Endpoint): ActiveSecureSession {
            while (true) {
                sessions[endpoint]?.let { return it }
                delay(10)
            }
        }

        suspend fun awaitFrames(endpoint: Endpoint, count: Int): Int {
            while ((frames[endpoint]?.get() ?: 0) < count) delay(10)
            return frames.getValue(endpoint).get()
        }

        fun shutdown() = scope.cancel()
    }

    private class PerPeerEndpointCache(private val byHash: Map<List<Byte>, List<Endpoint>>) : PeerEndpointCache {
        override suspend fun lookup(identityHash: ByteArray): List<Endpoint>? = byHash[identityHash.toList()]
        override suspend fun store(record: EndpointRecord) = Unit
        override suspend fun evict(identityHash: ByteArray) = Unit
    }

    private class FakeRelayDirectory : RelayDirectory {
        override suspend fun activeRelay() = SelectedRelay("wss://relay.invalid/relay", RelaySource.DEFAULT)
        override fun lastSelectedRelay(): SelectedRelay? = null
        override suspend fun reportResult(url: String, ok: Boolean) = Unit
    }

    private companion object {
        const val BOB = "contact-bob"
        const val CAROL = "contact-carol"
        const val CONNECT_DELAY_MS = 2_000L
        val BOB_ENDPOINT = Endpoint(TransportIds.INTERNET, "10.0.0.1:4000")
        val CAROL_ENDPOINT = Endpoint(TransportIds.INTERNET, "10.0.0.2:4000")
    }
}
