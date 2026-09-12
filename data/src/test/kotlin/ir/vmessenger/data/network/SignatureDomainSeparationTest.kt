package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.RelayProof
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.crypto.pairing.PairingDescriptorCodec
import ir.vmessenger.core.proto.app.v1.NodeRole
import ir.vmessenger.core.proto.app.v1.SignedNodeRecord
import ir.vmessenger.network.dht.EndpointRecordSigner
import ir.vmessenger.network.dht.EndpointRecordVerifier
import ir.vmessenger.network.dht.buildTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One identity key signs four kinds of v2 transcripts. Each starts with its own domain tag and
 * length-prefixes its fields, so a signature produced in one context can never be replayed as another —
 * even when the signed fields overlap byte-for-byte.
 */
class SignatureDomainSeparationTest {
    private lateinit var crypto: LazysodiumCryptoEngine
    private lateinit var key: KeyPair
    private lateinit var identityHash: ByteArray
    private lateinit var nodeVerifier: SignedNodeRecordVerifier

    @Before
    fun setUp() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        key = crypto.generateEd25519KeyPair()
        identityHash = crypto.sha256(key.publicKey)
        nodeVerifier = SignedNodeRecordVerifier(crypto)
    }

    @Test
    fun endpointRecordSignatureDoesNotVerifyAsNodeRecord() {
        val address = "wss://relay.example/relay"
        val expires = 1_800_000_000_000L
        val endpointRecord = EndpointRecordSigner(crypto).sign(
            identityHash = identityHash,
            identityPub = key.publicKey,
            endpoints = listOf(Endpoint(TransportIds.RELAY, address)),
            publishedAtUnixMs = expires - 60_000,
            ttlMs = 60_000,
            sequence = 1,
            ed25519PrivateKey = key.privateKey,
        )
        assertTrue(EndpointRecordVerifier(crypto).verify(endpointRecord, nowMs = expires - 1))

        // Same key, same address, same public key bytes: the endpoint signature must not authenticate a
        // node record (which would let a peer turn any published endpoint into a "signed" relay hint).
        val nodeRecord = SignedNodeRecord.newBuilder()
            .setAddress(address)
            .setRole(NodeRole.NODE_ROLE_RELAY)
            .setPublicKey(ByteString.copyFrom(key.publicKey))
            .addCapabilities("relay")
            .setExpiresAtUnixMs(expires)
            .setTranscriptVersion(SignedNodeRecordVerifier.TRANSCRIPT_VERSION)
            .setSignature(endpointRecord.signature)
            .build()
        assertNull(nodeVerifier.verify(nodeRecord, nowMs = expires - 1))

        val nodeSigned = SignedNodeRecordSigner(crypto, nodeVerifier).sign(
            address = address,
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = key.publicKey,
            capabilities = listOf("relay"),
            expiresAtUnixMs = expires,
            ed25519PrivateKey = key.privateKey,
        )
        assertNotNull(nodeVerifier.verify(nodeSigned, nowMs = expires - 1))
        assertFalse(
            EndpointRecordVerifier(crypto).verify(
                endpointRecord.toBuilder().setSignature(nodeSigned.signature).build(),
                nowMs = expires - 1,
            ),
        )
    }

    @Test
    fun relayProofDoesNotVerifyAsPairingOrEndpointRecord() {
        val ts = 1_800_000_000_000L
        val proof = crypto.signEd25519(
            RelayProof.buildListenerProofTranscript(identityHash, key.publicKey, ts),
            key.privateKey,
        )
        val codec = PairingDescriptorCodec(crypto)
        val descriptor = codec.createSigned(
            key.publicKey,
            UserHashEncoder.encode(identityHash),
            "label",
            key.privateKey,
        )
        assertTrue(codec.verify(descriptor))
        assertFalse(codec.verify(descriptor.toBuilder().setSignature(ByteString.copyFrom(proof)).build()))

        val endpointRecord = EndpointRecordSigner(crypto).sign(
            identityHash = identityHash,
            identityPub = key.publicKey,
            endpoints = emptyList(),
            publishedAtUnixMs = ts,
            ttlMs = 60_000,
            sequence = ts,
            ed25519PrivateKey = key.privateKey,
        )
        val forged = endpointRecord.toBuilder().setSignature(ByteString.copyFrom(proof)).build()
        assertFalse(EndpointRecordVerifier(crypto).verify(forged, nowMs = ts))
        assertFalse(crypto.verifyEd25519(endpointRecord.buildTranscript(), proof, key.publicKey))
    }

    @Test
    fun everyV2TranscriptStartsWithItsOwnTag() {
        val ts = 1_800_000_000_000L
        val codec = PairingDescriptorCodec(crypto)
        val pairing = codec.buildTranscript(
            codec.createSigned(key.publicKey, UserHashEncoder.encode(identityHash), "l", key.privateKey),
        )
        val endpoint = EndpointRecordSigner(crypto)
            .sign(identityHash, key.publicKey, emptyList(), ts, 1, 1, key.privateKey)
            .buildTranscript()
        val node = nodeVerifier.buildTranscript(
            SignedNodeRecord.newBuilder().setPublicKey(ByteString.copyFrom(key.publicKey)).build(),
        )
        val relay = RelayProof.buildListenerProofTranscript(identityHash, key.publicKey, ts)
        val tags = listOf(pairing, endpoint, node, relay).map { transcript ->
            String(transcript.copyOf(transcript.indexOfFirst { it == 0.toByte() }), Charsets.UTF_8)
        }
        assertEquals(
            listOf(
                "vmessenger-pairing-v2",
                "vmessenger-endpoint-record-v2",
                "vmessenger-node-record-v2",
                "vmessenger-relay-listener-v2",
            ),
            tags,
        )
        assertEquals(tags.size, tags.toSet().size)
    }
}
