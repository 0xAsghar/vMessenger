package ir.vmessenger.network.messaging

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.RelayProof
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.core.proto.relay.v1.RelayRole
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Mirrors what the relay node's `ListenerHandler` does with a LISTENER hello, using lazysodium's raw
 * API the way the node does, so the app-side factory and the node-side verifier cannot drift apart.
 */
class RelayHelloProofInteropTest {
    private val sodium = LazySodiumJava(SodiumJava())
    private val crypto = LazysodiumCryptoEngine(sodium)
    private val keyPair = crypto.generateEd25519KeyPair()
    private val identityHash = crypto.sha256(keyPair.publicKey)

    /** Node-style verification of a hello's proof against the transcript selected by `proof_version`. */
    private fun nodeVerifies(hello: RelayHello): Boolean {
        val listenerId = hello.listenerId.toByteArray()
        val identityPub = hello.identityPub.toByteArray()
        val transcript = when (hello.proofVersion) {
            RelayProof.PROOF_VERSION_V2 -> RelayProof.buildListenerProofTranscript(listenerId, identityPub, hello.ts)
            0, 1 -> RelayProof.buildLegacyListenerProofTranscript(listenerId, hello.ts)
            else -> return false
        }
        return sodium.cryptoSignVerifyDetached(hello.proof.toByteArray(), transcript, transcript.size, identityPub)
    }

    @Test
    fun v2ProofVerifiesWithNodeSodium() {
        val hello = RelayHelloFactory(crypto).buildListenerHello(identityHash, keyPair.publicKey, keyPair.privateKey)
        assertEquals(RelayRole.RELAY_ROLE_LISTENER, hello.role)
        assertEquals(2, hello.proofVersion)
        assertArrayEquals(identityHash, hello.listenerId.toByteArray())
        assertArrayEquals(keyPair.publicKey, hello.identityPub.toByteArray())
        assertTrue(abs(System.currentTimeMillis() - hello.ts) < 5_000)
        assertTrue(nodeVerifies(hello))
    }

    @Test
    fun v1TranscriptFailsV2Verify() {
        val ts = System.currentTimeMillis()
        val legacyTranscript = RelayProof.buildLegacyListenerProofTranscript(identityHash, ts)
        val legacyProof = crypto.signEd25519(legacyTranscript, keyPair.privateKey)
        val v2Transcript = RelayProof.buildListenerProofTranscript(identityHash, keyPair.publicKey, ts)
        assertFalse(sodium.cryptoSignVerifyDetached(legacyProof, v2Transcript, v2Transcript.size, keyPair.publicKey))

        // A legacy proof re-labelled as proof_version 2 is rejected; as 0/1 the node still accepts it.
        val legacyHello = RelayHello.newBuilder()
            .setRole(RelayRole.RELAY_ROLE_LISTENER)
            .setListenerId(com.google.protobuf.ByteString.copyFrom(identityHash))
            .setIdentityPub(com.google.protobuf.ByteString.copyFrom(keyPair.publicKey))
            .setProof(com.google.protobuf.ByteString.copyFrom(legacyProof))
            .setTs(ts)
        assertFalse(nodeVerifies(legacyHello.setProofVersion(2).build()))
        assertTrue(nodeVerifies(legacyHello.setProofVersion(0).build()))
        assertTrue(nodeVerifies(legacyHello.setProofVersion(1).build()))
        assertFalse(nodeVerifies(legacyHello.setProofVersion(3).build()))
    }

    @Test
    fun v2ProofBindsIdentityPubAndTimestamp() {
        val hello = RelayHelloFactory(crypto).buildListenerHello(identityHash, keyPair.publicKey, keyPair.privateKey)
        val other = crypto.generateEd25519KeyPair()
        val swappedPub = hello.toBuilder().setIdentityPub(com.google.protobuf.ByteString.copyFrom(other.publicKey))
        assertFalse(nodeVerifies(swappedPub.build()))
        assertFalse(nodeVerifies(hello.toBuilder().setTs(hello.ts + 1).build()))
        assertFalse(nodeVerifies(hello.toBuilder().setProofVersion(1).build()))
    }
}
