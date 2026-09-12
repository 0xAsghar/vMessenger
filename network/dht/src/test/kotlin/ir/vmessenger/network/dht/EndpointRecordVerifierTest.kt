package ir.vmessenger.network.dht

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.EndpointRecordTranscript
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EndpointRecordVerifierTest {
    private lateinit var signer: EndpointRecordSigner
    private lateinit var verifier: EndpointRecordVerifier
    private lateinit var crypto: LazysodiumCryptoEngine
    private lateinit var ed: KeyPair

    @Before
    fun setup() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        signer = EndpointRecordSigner(crypto)
        verifier = EndpointRecordVerifier(crypto)
        ed = crypto.generateEd25519KeyPair()
    }

    private fun signedRecord(
        endpoints: List<Endpoint> = listOf(Endpoint(TransportIds.INTERNET, "127.0.0.1:46555")),
    ): EndpointRecord = signer.sign(
        identityHash = crypto.sha256(ed.publicKey),
        identityPub = ed.publicKey,
        endpoints = endpoints,
        publishedAtUnixMs = System.currentTimeMillis(),
        ttlMs = 60_000,
        sequence = 1,
        ed25519PrivateKey = ed.privateKey,
    )

    @Test
    fun verifyValidRecord() {
        val record = signedRecord()
        assertEquals(2, record.transcriptVersion)
        assertTrue(verifier.verify(record))
    }

    @Test
    fun rejectTamperedRecord() {
        val record = signedRecord()
        assertFalse(verifier.verify(record.toBuilder().setSequence(99).build()))
        assertFalse(verifier.verify(record.toBuilder().setTtlMs(record.ttlMs + 1).build()))
        assertFalse(verifier.verify(record.toBuilder().removeEndpoints(0).build()))
        assertFalse(verifier.verify(record.toBuilder().setSignature(ByteString.copyFrom(ByteArray(64))).build()))
    }

    @Test
    fun transcriptVersionOneRejected() {
        val record = signedRecord()
        // Same valid signature, but the record claims another transcript version: never accepted.
        assertFalse(verifier.verify(record.toBuilder().setTranscriptVersion(1).build()))
        assertFalse(verifier.verify(record.toBuilder().setTranscriptVersion(0).build()))
        assertFalse(verifier.verify(record.toBuilder().setTranscriptVersion(3).build()))

        // A genuine 0.x record (legacy transcript, version unset) is rejected by the app too.
        val unsigned = record.toBuilder().clearSignature().clearTranscriptVersion().build()
        val legacyTranscript = EndpointRecordTranscript.buildLegacy(
            identityHash = unsigned.identityHash.toByteArray(),
            identityPub = unsigned.identityPub.toByteArray(),
            endpoints = unsigned.endpointsList.map { EndpointRecordTranscript.Entry(it.transport, it.address) },
            publishedAtUnixMs = unsigned.publishedAtUnixMs,
            ttlMs = unsigned.ttlMs,
            sequence = unsigned.sequence,
        )
        val legacySignature = crypto.signEd25519(legacyTranscript, ed.privateKey)
        val legacy = unsigned.toBuilder().setSignature(ByteString.copyFrom(legacySignature)).build()
        assertFalse(verifier.verify(legacy))
    }

    @Test
    fun rejectExpiredRecord() {
        val record = signer.sign(
            identityHash = crypto.sha256(ed.publicKey),
            identityPub = ed.publicKey,
            endpoints = listOf(Endpoint(TransportIds.INTERNET, "127.0.0.1:46555")),
            publishedAtUnixMs = 1_000,
            ttlMs = 60_000,
            sequence = 1,
            ed25519PrivateKey = ed.privateKey,
        )
        assertTrue(verifier.verify(record, nowMs = 60_999))
        assertFalse(verifier.verify(record, nowMs = 61_000))
    }

    private fun signedAt(publishedAtUnixMs: Long, ttlMs: Long): EndpointRecord = signer.sign(
        identityHash = crypto.sha256(ed.publicKey),
        identityPub = ed.publicKey,
        endpoints = listOf(Endpoint(TransportIds.INTERNET, "127.0.0.1:46555")),
        publishedAtUnixMs = publishedAtUnixMs,
        ttlMs = ttlMs,
        sequence = 1,
        ed25519PrivateKey = ed.privateKey,
    )

    @Test
    fun ttlAboveMaxRejected() {
        val now = 1_000_000L
        val max = EndpointRecordVerifier.MAX_TTL_MS
        assertTrue(verifier.verify(signedAt(publishedAtUnixMs = now, ttlMs = max), nowMs = now))
        // Correctly signed, but claims to live longer than a day: never accepted.
        assertFalse(verifier.verify(signedAt(publishedAtUnixMs = now, ttlMs = max + 1), nowMs = now))
        assertFalse(verifier.verify(signedAt(publishedAtUnixMs = now, ttlMs = Long.MAX_VALUE), nowMs = now))
        // Zero / negative TTLs are rejected before any expiry arithmetic.
        assertFalse(verifier.verify(signedAt(publishedAtUnixMs = now, ttlMs = 0), nowMs = now))
        assertFalse(verifier.verify(signedAt(publishedAtUnixMs = now, ttlMs = -60_000), nowMs = now))
    }

    @Test
    fun publishedInFutureRejected() {
        val now = 1_000_000L
        val skew = EndpointRecordVerifier.MAX_FUTURE_SKEW_MS
        assertTrue(verifier.verify(signedAt(publishedAtUnixMs = now + skew, ttlMs = 60_000), nowMs = now))
        // Beyond the tolerated clock skew a record cannot be trusted (it would also outlive its TTL).
        assertFalse(verifier.verify(signedAt(publishedAtUnixMs = now + skew + 1, ttlMs = 60_000), nowMs = now))
        assertFalse(verifier.verify(signedAt(publishedAtUnixMs = Long.MAX_VALUE, ttlMs = 60_000), nowMs = now))
    }

    @Test
    fun endpointOrderDoesNotAffectSignature() {
        val a = Endpoint(TransportIds.RELAY, "wss://b.example/relay")
        val b = Endpoint(TransportIds.INTERNET, "10.0.0.1:1")
        val first = signedRecord(listOf(a, b))
        val swapped = first.toBuilder()
            .clearEndpoints()
            .addEndpoints(first.getEndpoints(1))
            .addEndpoints(first.getEndpoints(0))
            .build()
        assertArrayEquals(first.buildTranscript(), swapped.buildTranscript())
        assertTrue(verifier.verify(swapped))
    }
}
