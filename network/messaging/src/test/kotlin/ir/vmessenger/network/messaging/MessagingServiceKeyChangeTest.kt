package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppError
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Initiator side of X25519 static-key pinning through [MessagingService]: when the
 * contact we dial presents a key other than the pinned one, the new key must be
 * recorded as *pending* (never adopted), the send must fail with a dedicated
 * [AppError.Security], and the remaining endpoints must not be dialled.
 */
class MessagingServiceKeyChangeTest {
    private lateinit var crypto: CryptoEngine
    private lateinit var alice: PeerIdentity
    private lateinit var bob: PeerIdentity
    private lateinit var transport: PipedTransport
    private lateinit var service: MessagingService
    private val recorded = CopyOnWriteArrayList<Pair<String, ByteArray>>()

    @Before
    fun setUp() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        val factory = SecureChannelFactory(crypto, SymmetricRatchet(crypto))
        alice = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        bob = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        // Bob (responder) runs with his real keys and has Alice's genuine key pinned.
        transport = PipedTransport { server -> factory.accept(server, bob, alice) }
        val relayTransport = RelayTransport()
        P2PConfig.peerCacheEnabled = true
        service = MessagingService(
            endpointResolveService = EndpointResolveService(
                FakePeerEndpointCache(listOf(ENDPOINT_A, ENDPOINT_B)),
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
            peerKeyChangeRecorder = { contactId, newStaticKey -> recorded += contactId to newStaticKey },
        )
    }

    @After
    fun tearDown() {
        transport.shutdown()
        P2PConfig.resetToDefaults()
    }

    @Test
    fun matchingPinnedKeySendsWithoutRecordingAnything() = runBlocking {
        val result = service.sendToEndpoint(CONTACT, alice, bob, ENDPOINT_A, envelope("hi"))
        assertTrue("expected success, got $result", result is AppResult.Success)
        assertTrue(recorded.isEmpty())
        assertEquals(1, transport.dials.get())
    }

    @Test
    fun changedPinnedKeyIsRecordedAsPendingOnInitiatorPath() = runBlocking {
        val staleBob = bob.copy(x25519StaticPublicKey = crypto.generateX25519KeyPair().publicKey)
        val result = service.sendToEndpoint(CONTACT, alice, staleBob, ENDPOINT_A, envelope("hi"))
        val error = (result as? AppResult.Error)?.error
        assertNotNull("expected an error, got $result", error)
        assertTrue("expected AppError.Security, got $error", error is AppError.Security)
        assertEquals(1, recorded.size)
        val (contactId, newStaticKey) = recorded.single()
        assertEquals(CONTACT, contactId)
        assertTrue(
            "recorder must receive the key the peer presented",
            newStaticKey.contentEquals(bob.x25519StaticPublicKey),
        )
    }

    @Test
    fun changedPinnedKeyStopsEndpointFallback() = runBlocking {
        val staleBob = bob.copy(x25519StaticPublicKey = crypto.generateX25519KeyPair().publicKey)
        val result = service.send(CONTACT, alice, staleBob, envelope("hi"))
        val error = (result as? AppResult.Error)?.error
        assertTrue("expected AppError.Security, got $result", error is AppError.Security)
        // The peer's key is the same on every endpoint: one dial, one pending record.
        assertEquals(1, transport.dials.get())
        assertEquals(1, recorded.size)
        assertTrue(recorded.single().second.contentEquals(bob.x25519StaticPublicKey))
    }

    private fun envelope(text: String): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("id-$text"))
        .setSentAtUnixMs(System.currentTimeMillis())
        .setCounter(1)
        .setChat(ir.vmessenger.core.proto.app.v1.ChatMessage.newBuilder().setText(text))
        .build()

    private fun identity(ed: KeyPair, x: KeyPair) = PeerIdentity(
        identityHash = crypto.sha256(ed.publicKey),
        ed25519PublicKey = ed.publicKey,
        x25519StaticPublicKey = x.publicKey,
        ed25519PrivateKey = ed.privateKey,
        x25519StaticPrivateKey = x.privateKey,
    )

    /** INTERNET transport whose every dial is an in-memory pipe answered by [responder]. */
    private class PipedTransport(
        private val responder: suspend (server: Connection) -> Unit,
    ) : Transport {
        override val id: TransportId = TransportIds.INTERNET
        override val capabilities = TransportCapabilities(reliable = true, ordered = true, mtu = 65_535)
        val dials = AtomicInteger()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        override fun canReach(endpoint: Endpoint): Boolean = endpoint.transport == TransportIds.INTERNET

        override suspend fun connect(endpoint: Endpoint): Result<Connection> {
            dials.incrementAndGet()
            val (client, server) = pairedConnections()
            scope.launch { runCatching { responder(server) } }
            return Result.success(client)
        }

        override fun listen(port: Int): Flow<Connection> = emptyFlow()

        fun shutdown() = scope.cancel()
    }

    private class FakePeerEndpointCache(private val endpoints: List<Endpoint>) : PeerEndpointCache {
        override suspend fun lookup(identityHash: ByteArray): List<Endpoint> = endpoints
        override suspend fun store(record: EndpointRecord) = Unit
        override suspend fun evict(identityHash: ByteArray) = Unit
    }

    private class FakeRelayDirectory : RelayDirectory {
        override suspend fun activeRelay() = SelectedRelay("wss://relay.invalid/relay", RelaySource.DEFAULT)
        override fun lastSelectedRelay(): SelectedRelay? = null
        override suspend fun reportResult(url: String, ok: Boolean) = Unit
    }

    private companion object {
        const val CONTACT = "contact-1"
        val ENDPOINT_A = Endpoint(TransportIds.INTERNET, "10.0.0.1:4000")
        val ENDPOINT_B = Endpoint(TransportIds.INTERNET, "10.0.0.2:4000")
    }
}
