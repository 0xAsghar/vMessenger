package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.RelaySource
import ir.vmessenger.core.common.network.SelectedRelay
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.ChatMessage
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.network.discovery.DiscoveryManager
import ir.vmessenger.network.discovery.EndpointResolveService
import ir.vmessenger.network.discovery.PeerEndpointCache
import ir.vmessenger.network.transport.InternetTransport
import ir.vmessenger.network.transport.RelayTransport
import ir.vmessenger.network.transport.TransportSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * A peer that was an unknown stranger when its session opened can become a real
 * contact while that session is still live — exactly what happens when the user
 * approves the contact request that arrived over it. The session must re-bind to
 * the approved contact id, otherwise every later frame is still attributed to
 * the stranger and dropped downstream as "non-approved" until the peer happens
 * to re-handshake.
 */
class MessagingServiceProvisionalContactTest {
    private lateinit var crypto: CryptoEngine
    private lateinit var factory: SecureChannelFactory
    private lateinit var alice: PeerIdentity
    private lateinit var bob: PeerIdentity
    private lateinit var service: MessagingService
    private val received = CopyOnWriteArrayList<String>()
    private val resolved = AtomicReference(STRANGER_ID)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        factory = SecureChannelFactory(crypto, SymmetricRatchet(crypto))
        alice = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        bob = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        val relayTransport = RelayTransport()
        service = MessagingService(
            endpointResolveService = EndpointResolveService(
                FakePeerEndpointCache(),
                DiscoveryManager(emptySet()),
            ),
            sessionPostHandshakeHandler = SessionPostHandshakeHandler { _, _, _ -> },
            transportSelector = TransportSelector(emptySet(), relayTransport),
            secureChannelFactory = factory,
            internetTransport = InternetTransport(),
            relayListener = RelayListener(relayTransport, RelayHelloFactory(crypto), FakeRelayDirectory()),
        )
        service.configureInbound(
            selfProvider = { alice },
            resolveInboundPeer = { _, _ -> bob },
            contactIdResolver = { resolved.get() },
            isProvisionalContactId = { it.startsWith("stranger:") },
        )
        service.setIncomingSink { incoming -> received += incoming.contactId }
    }

    @After
    fun tearDown() {
        scope.cancel()
        P2PConfig.resetToDefaults()
    }

    @Test
    fun sessionRebindsWhenStrangerBecomesApprovedContact() = runBlocking {
        val (client, server) = pairedConnections()
        // Alice serves the inbound side; Bob dials in while still a stranger.
        scope.launch { runCatching { service.acceptInbound(server) } }
        val session = factory.initiate(client, bob, alice).getOrThrow() as ActiveSecureSession

        session.writeSealed(envelope("before-approval"))
        withTimeout(TIMEOUT_MS) { awaitCount(1) }
        assertEquals("first frame belongs to the stranger", listOf(STRANGER_ID), received.toList())

        // The user approves the request: the stranger now has a real contact row.
        resolved.set(APPROVED_ID)

        session.writeSealed(envelope("after-approval"))
        withTimeout(TIMEOUT_MS) { awaitCount(2) }
        assertEquals(
            "the live session must follow the peer to its approved contact id",
            listOf(STRANGER_ID, APPROVED_ID),
            received.toList(),
        )
    }

    @Test
    fun approvedContactIdIsNotReResolvedPerFrame() = runBlocking {
        var resolverCalls = 0
        service.configureInbound(
            selfProvider = { alice },
            resolveInboundPeer = { _, _ -> bob },
            contactIdResolver = {
                resolverCalls++
                APPROVED_ID
            },
            isProvisionalContactId = { it.startsWith("stranger:") },
        )
        val (client, server) = pairedConnections()
        scope.launch { runCatching { service.acceptInbound(server) } }
        val session = factory.initiate(client, bob, alice).getOrThrow() as ActiveSecureSession

        repeat(3) { session.writeSealed(envelope("msg-$it")) }
        withTimeout(TIMEOUT_MS) { awaitCount(3) }

        // Once resolved to a real contact the id is stable: only the handshake lookup.
        assertEquals(1, resolverCalls)
        assertEquals(listOf(APPROVED_ID, APPROVED_ID, APPROVED_ID), received.toList())
    }

    private suspend fun awaitCount(n: Int) {
        while (received.size < n) kotlinx.coroutines.delay(POLL_MS)
    }

    private fun envelope(text: String): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("id-$text"))
        .setSenderIdentityHash(ByteString.copyFrom(bob.identityHash))
        .setSentAtUnixMs(System.currentTimeMillis())
        .setChat(ChatMessage.newBuilder().setText(text))
        .build()

    private fun identity(ed: KeyPair, x: KeyPair) = PeerIdentity(
        identityHash = crypto.sha256(ed.publicKey),
        ed25519PublicKey = ed.publicKey,
        x25519StaticPublicKey = x.publicKey,
        ed25519PrivateKey = ed.privateKey,
        x25519StaticPrivateKey = x.privateKey,
    )

    private class FakePeerEndpointCache : PeerEndpointCache {
        override suspend fun lookup(identityHash: ByteArray): List<Endpoint> = emptyList()
        override suspend fun store(record: EndpointRecord) = Unit
        override suspend fun evict(identityHash: ByteArray) = Unit
    }

    private class FakeRelayDirectory : RelayDirectory {
        override suspend fun activeRelay() = SelectedRelay("wss://relay.invalid/relay", RelaySource.DEFAULT)
        override fun lastSelectedRelay(): SelectedRelay? = null
        override suspend fun reportResult(url: String, ok: Boolean) = Unit
    }

    private companion object {
        const val STRANGER_ID = "stranger:deadbeef"
        const val APPROVED_ID = "contact-approved"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
