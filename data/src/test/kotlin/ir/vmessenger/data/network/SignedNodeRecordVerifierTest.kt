package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.NodeTrust
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.NodeRole
import ir.vmessenger.core.proto.app.v1.SignedNodeRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SignedNodeRecordVerifierTest {
    private lateinit var cryptoEngine: CryptoEngine
    private lateinit var verifier: SignedNodeRecordVerifier
    private lateinit var signer: SignedNodeRecordSigner

    @Before
    fun setUp() {
        cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        verifier = SignedNodeRecordVerifier(cryptoEngine)
        signer = SignedNodeRecordSigner(cryptoEngine, verifier)
    }

    @Test
    fun validSignedRecordPassesVerification() {
        val keyPair = cryptoEngine.generateEd25519KeyPair()
        val expires = System.currentTimeMillis() + 60_000
        val record = signer.sign(
            address = "wss://node.example/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = keyPair.publicKey,
            capabilities = listOf("relay"),
            expiresAtUnixMs = expires,
            ed25519PrivateKey = keyPair.privateKey,
        )
        assertEquals(2, record.transcriptVersion)
        assertEquals(NodeTrust.COMMUNITY, verifier.verify(record, nowMs = System.currentTimeMillis()))
    }

    @Test
    fun operatorKeyYieldsOfficial() {
        val operator = cryptoEngine.generateEd25519KeyPair()
        val other = cryptoEngine.generateEd25519KeyPair()
        val anchored = SignedNodeRecordVerifier(cryptoEngine, operator.publicKey)
        val anchoredSigner = SignedNodeRecordSigner(cryptoEngine, anchored)
        val expires = System.currentTimeMillis() + 60_000
        val official = anchoredSigner.sign(
            address = "wss://relay.vmessenger.ir/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = operator.publicKey,
            capabilities = listOf("relay"),
            expiresAtUnixMs = expires,
            ed25519PrivateKey = operator.privateKey,
        )
        assertEquals(NodeTrust.OFFICIAL, anchored.verify(official))
        // Claiming the operator key without its signature is just an invalid record.
        val forged = official.toBuilder().setAddress("wss://evil.example/relay").build()
        assertNull(anchored.verify(forged))
        val community = anchoredSigner.sign(
            address = "wss://community.example/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = other.publicKey,
            capabilities = listOf("relay"),
            expiresAtUnixMs = expires,
            ed25519PrivateKey = other.privateKey,
        )
        assertEquals(NodeTrust.COMMUNITY, anchored.verify(community))
        // Without a configured anchor (placeholder constant) nothing is official.
        assertEquals(NodeTrust.COMMUNITY, SignedNodeRecordVerifier(cryptoEngine, null).verify(official))
        assertEquals(NodeTrust.COMMUNITY, verifier.verify(official))
    }

    @Test
    fun expiredRecordFailsVerification() {
        val keyPair = cryptoEngine.generateEd25519KeyPair()
        val record = signer.sign(
            address = "wss://node.example/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = keyPair.publicKey,
            capabilities = emptyList(),
            expiresAtUnixMs = System.currentTimeMillis() - 1,
            ed25519PrivateKey = keyPair.privateKey,
        )
        assertNull(verifier.verify(record, nowMs = System.currentTimeMillis()))
    }

    @Test
    fun tamperedRecordFailsVerification() {
        val keyPair = cryptoEngine.generateEd25519KeyPair()
        val record = signer.sign(
            address = "wss://node.example/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = keyPair.publicKey,
            capabilities = listOf("relay"),
            expiresAtUnixMs = System.currentTimeMillis() + 60_000,
            ed25519PrivateKey = keyPair.privateKey,
        )
        assertNull(verifier.verify(record.toBuilder().setAddress("wss://evil.example/relay").build()))
        assertNull(verifier.verify(record.toBuilder().setRole(NodeRole.NODE_ROLE_BOOTSTRAP).build()))
        assertNull(verifier.verify(record.toBuilder().addCapabilities("bootstrap").build()))
        assertNull(verifier.verify(record.toBuilder().setExpiresAtUnixMs(record.expiresAtUnixMs + 1).build()))
    }

    @Test
    fun unknownOrNegativeRoleValueIsRejectedWithoutThrowing() {
        val keyPair = cryptoEngine.generateEd25519KeyPair()
        val record = signer.sign(
            address = "wss://node.example/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = keyPair.publicKey,
            capabilities = listOf("relay"),
            expiresAtUnixMs = System.currentTimeMillis() + 60_000,
            ed25519PrivateKey = keyPair.privateKey,
        )
        // proto enum accessors return the raw wire value for unknown roles, including negatives.
        assertNull(verifier.verify(record.toBuilder().setRoleValue(-1).build()))
        assertNull(verifier.verify(record.toBuilder().setRoleValue(Int.MIN_VALUE).build()))
        assertNull(verifier.verify(record.toBuilder().setRoleValue(99).build()))
        // A record legitimately signed over an out-of-enum role still round-trips.
        val unsigned = record.toBuilder().clearSignature().setRoleValue(-1).build()
        val signature = cryptoEngine.signEd25519(verifier.buildTranscript(unsigned), keyPair.privateKey)
        assertNotNull(verifier.verify(unsigned.toBuilder().setSignature(ByteString.copyFrom(signature)).build()))
    }

    @Test
    fun transcriptVersionMismatchRejected() {
        val keyPair = cryptoEngine.generateEd25519KeyPair()
        val record = signer.sign(
            address = "wss://node.example/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = keyPair.publicKey,
            capabilities = listOf("relay"),
            expiresAtUnixMs = System.currentTimeMillis() + 60_000,
            ed25519PrivateKey = keyPair.privateKey,
        )
        assertNull(verifier.verify(record.toBuilder().setTranscriptVersion(0).build()))
        assertNull(verifier.verify(record.toBuilder().setTranscriptVersion(1).build()))
        assertNull(verifier.verify(record.toBuilder().setTranscriptVersion(3).build()))

        // A 0.x record signed the old "address|role|expires|caps" string: never accepted, whatever it claims.
        val unsigned = record.toBuilder().clearSignature().clearTranscriptVersion().build()
        val legacyTranscript = "${unsigned.address}|relay|${unsigned.expiresAtUnixMs}|relay".toByteArray()
        val legacySignature = cryptoEngine.signEd25519(legacyTranscript, keyPair.privateKey)
        val legacy = unsigned.toBuilder().setSignature(ByteString.copyFrom(legacySignature))
        assertNull(verifier.verify(legacy.build()))
        assertNull(verifier.verify(legacy.setTranscriptVersion(2).build()))
    }

    @Test
    fun transcriptIsDomainSeparatedAndLengthPrefixed() {
        val record = SignedNodeRecord.newBuilder()
            .setAddress("wss://a/r")
            .setRole(NodeRole.NODE_ROLE_BOOTSTRAP)
            .setPublicKey(ByteString.copyFrom(ByteArray(32) { 7 }))
            .addCapabilities("x")
            .addCapabilities("yz")
            .setExpiresAtUnixMs(0x0102030405060708L)
            .setTranscriptVersion(2)
            .build()
        val transcript = verifier.buildTranscript(record)
        val tag = "vmessenger-node-record-v2".toByteArray()
        assertArrayEquals(tag, transcript.copyOf(tag.size))
        assertEquals(tag.size + (4 + 9) + 4 + (4 + 32) + 4 + (4 + 1) + (4 + 2) + 8, transcript.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), transcript.copyOfRange(tag.size + 13, tag.size + 17))
        val expires = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertArrayEquals(expires, transcript.copyOfRange(transcript.size - 8, transcript.size))
    }
}
