package ir.vmessenger.network.messaging

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecureChannelFactoryTest {
    private lateinit var crypto: CryptoEngine
    private lateinit var factory: SecureChannelFactory

    @Before
    fun setUp() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        factory = SecureChannelFactory(crypto, SymmetricRatchet(crypto))
    }

    @Test
    fun handshakeRoundTrip() = runBlocking {
        val aliceEd = crypto.generateEd25519KeyPair()
        val aliceX = crypto.generateX25519KeyPair()
        val bobEd = crypto.generateEd25519KeyPair()
        val bobX = crypto.generateX25519KeyPair()
        val aliceHash = crypto.sha256(aliceEd.publicKey)
        val bobHash = crypto.sha256(bobEd.publicKey)
        val alice = peer(aliceHash, aliceEd, aliceX)
        val bob = peer(bobHash, bobEd, bobX)
        val (client, server) = pairedConnections()

        val serverDeferred = async(Dispatchers.Default) {
            factory.accept(server, bob, alice).getOrThrow()
        }
        val clientSession = factory.initiate(client, alice, bob).getOrThrow()
        val serverSession = serverDeferred.await()
        clientSession.close()
        serverSession.close()
    }

    @Test
    fun initiateLearnsPeerKeysFromHandshake() = runBlocking {
        val aliceEd = crypto.generateEd25519KeyPair()
        val aliceX = crypto.generateX25519KeyPair()
        val bobEd = crypto.generateEd25519KeyPair()
        val bobX = crypto.generateX25519KeyPair()
        val bobHash = crypto.sha256(bobEd.publicKey)
        val aliceHash = crypto.sha256(aliceEd.publicKey)
        val alice = peer(aliceHash, aliceEd, aliceX)
        val placeholderBob = PeerIdentity(
            identityHash = bobHash.copyOf(32).also { bobHash.copyInto(it, 0, 0, 16) },
            ed25519PublicKey = ByteArray(32),
            x25519StaticPublicKey = ByteArray(32),
            ed25519PrivateKey = null,
            x25519StaticPrivateKey = null,
        )
        val bob = peer(bobHash, bobEd, bobX)
        val (client, server) = pairedConnections()

        val serverDeferred = async(Dispatchers.Default) {
            factory.acceptResolving(server, bob) { identityPub, staticPub ->
                require(identityPub.contentEquals(aliceEd.publicKey))
                alice.copy(x25519StaticPublicKey = staticPub)
            }.getOrThrow()
        }
        val clientSession = factory.initiate(client, alice, placeholderBob).getOrThrow()
        val serverSession = serverDeferred.await()

        assertTrue(clientSession.peer.ed25519PublicKey.contentEquals(bobEd.publicKey))
        assertTrue(clientSession.peer.x25519StaticPublicKey.contentEquals(bobX.publicKey))
        clientSession.close()
        serverSession.close()
    }

    private fun peer(hash: ByteArray, ed: KeyPair, x: KeyPair) = PeerIdentity(
        identityHash = hash,
        ed25519PublicKey = ed.publicKey,
        x25519StaticPublicKey = x.publicKey,
        ed25519PrivateKey = ed.privateKey,
        x25519StaticPrivateKey = x.privateKey,
    )

    private fun pairedConnections(): Pair<PipedConnection, PipedConnection> {
        val clientToServer = Channel<ByteArray>(8)
        val serverToClient = Channel<ByteArray>(8)
        return PipedConnection(serverToClient, clientToServer) to
            PipedConnection(clientToServer, serverToClient)
    }
}

private class PipedConnection(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
) : ir.vmessenger.network.transport.Connection {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        ir.vmessenger.network.transport.ConnectionState.OPEN,
    )
    override val remote = ir.vmessenger.core.common.network.Endpoint(
        ir.vmessenger.core.common.network.TransportIds.INTERNET,
        "piped://test",
    )
    override val state: kotlinx.coroutines.flow.StateFlow<ir.vmessenger.network.transport.ConnectionState> = _state

    override suspend fun write(frame: ByteArray): Result<Unit> = runCatching {
        outgoing.send(frame)
    }

    override fun read() = incoming.receiveAsFlow()

    override suspend fun close() {
        _state.value = ir.vmessenger.network.transport.ConnectionState.CLOSED
        incoming.close()
        outgoing.close()
    }
}
