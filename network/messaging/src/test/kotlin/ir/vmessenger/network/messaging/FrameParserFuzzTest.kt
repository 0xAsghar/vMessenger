package ir.vmessenger.network.messaging

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import kotlin.random.Random

/**
 * Fuzz-lite: random and truncated inputs through every inbound parser must be
 * rejected by the guards, never escape as an exception.
 */
class FrameParserFuzzTest {
    private val crypto: CryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val factory = SecureChannelFactory(crypto, SymmetricRatchet(crypto))
    private val random = Random(20_260_911)

    @Test
    fun secureFrameGuardNeverThrows() = runBlocking {
        val (alice, bob) = sessions()
        val guard = SecureFrameGuard()
        alice.writeSealed(MessageEnvelope.newBuilder().setCounter(1).build())
        val genuine = bob.connection.read().first()
        repeat(ITERATIONS) { i ->
            val input = mutate(genuine, i)
            try {
                guard.process(bob, "fuzz", input)
            } catch (t: Throwable) {
                fail("iteration $i (${input.size} B) escaped the guard: $t")
            }
        }
    }

    @Test
    fun handshakeAcceptNeverThrows() = runBlocking {
        val bob = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        repeat(HANDSHAKE_ITERATIONS) { i ->
            val (client, server) = pairedConnections()
            client.write(mutate(ByteArray(0), i)).getOrThrow()
            val result = factory.acceptResolving(server, bob) { _, _ -> null }
            assertTrue("iteration $i must fail cleanly", result.isFailure)
            client.close()
            server.close()
        }
    }

    @Test
    fun lengthPrefixedReaderOnlyThrowsGuardErrors() {
        repeat(ITERATIONS) { i ->
            val input = mutate(LengthPrefixedFrames.encode(ByteArray(random.nextInt(0, 64))), i)
            try {
                LengthPrefixedFrames.readFrame(ByteArrayInputStream(input))
            } catch (_: IllegalArgumentException) {
                // invalid length guard
            } catch (_: EOFException) {
                // truncated payload guard
            }
        }
    }

    /** Random bytes, a truncated genuine frame, or a genuine frame with flipped bytes. */
    private fun mutate(genuine: ByteArray, i: Int): ByteArray = when (i % 3) {
        0 -> random.nextBytes(random.nextInt(0, MAX_RANDOM_BYTES))
        1 -> if (genuine.isEmpty()) ByteArray(0) else genuine.copyOf(random.nextInt(0, genuine.size))
        else -> genuine.copyOf().also { bytes ->
            repeat(random.nextInt(1, 4)) {
                if (bytes.isNotEmpty()) {
                    val at = random.nextInt(bytes.size)
                    bytes[at] = (bytes[at].toInt() xor random.nextInt(1, 256)).toByte()
                }
            }
        }
    }

    private suspend fun sessions(): Pair<ActiveSecureSession, ActiveSecureSession> {
        val alice = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        val bob = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        val (client, server) = pairedConnections()
        return kotlinx.coroutines.coroutineScope {
            val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, alice).getOrThrow() }
            val clientSession = factory.initiate(client, alice, bob).getOrThrow() as ActiveSecureSession
            clientSession to serverDeferred.await() as ActiveSecureSession
        }
    }

    private fun identity(ed: KeyPair, x: KeyPair) = PeerIdentity(
        identityHash = crypto.sha256(ed.publicKey),
        ed25519PublicKey = ed.publicKey,
        x25519StaticPublicKey = x.publicKey,
        ed25519PrivateKey = ed.privateKey,
        x25519StaticPrivateKey = x.privateKey,
    )

    private companion object {
        const val ITERATIONS = 10_000
        const val HANDSHAKE_ITERATIONS = 1_000
        const val MAX_RANDOM_BYTES = 512
    }
}
