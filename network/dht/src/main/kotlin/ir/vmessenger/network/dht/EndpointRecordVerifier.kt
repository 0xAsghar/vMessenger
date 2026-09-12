package ir.vmessenger.network.dht

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.EndpointRecordTranscript
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import javax.inject.Inject

/**
 * Accepts a record only when it is a v2 transcript, self-consistent (hash of the
 * key), correctly signed, and its validity window is sane: `ttl_ms` in
 * `1..MAX_TTL_MS`, `published_at` no further than [MAX_FUTURE_SKEW_MS] ahead of
 * the local clock, and not yet expired.
 */
class EndpointRecordVerifier @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    @Suppress("ReturnCount") // early-exit validation chain
    fun verify(record: EndpointRecord, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (record.transcriptVersion != EndpointRecordTranscript.TRANSCRIPT_VERSION_V2) return false
        if (record.identityHash.size() != HASH_SIZE || record.identityPub.size() != HASH_SIZE) return false
        if (!validityWindowOk(record, nowMs)) return false
        val computedHash = cryptoEngine.sha256(record.identityPub.toByteArray())
        if (!computedHash.contentEquals(record.identityHash.toByteArray())) return false
        val transcript = record.buildTranscript()
        return cryptoEngine.verifyEd25519(
            transcript,
            record.signature.toByteArray(),
            record.identityPub.toByteArray(),
        )
    }

    private fun validityWindowOk(record: EndpointRecord, nowMs: Long): Boolean {
        val ttl = record.ttlMs
        val publishedAt = record.publishedAtUnixMs
        return ttl in 1..MAX_TTL_MS &&
            publishedAt <= nowMs + MAX_FUTURE_SKEW_MS &&
            publishedAt + ttl > nowMs
    }

    companion object {
        private const val HASH_SIZE = 32

        /** Longest lifetime a record may claim (24 h). */
        const val MAX_TTL_MS = 24 * 60 * 60 * 1000L

        /** Tolerated clock skew for `published_at` in the future (5 min). */
        const val MAX_FUTURE_SKEW_MS = 5 * 60 * 1000L
    }
}

class EndpointRecordSigner(
    private val cryptoEngine: CryptoEngine,
) {
    @Suppress("LongParameterList")
    fun sign(
        identityHash: ByteArray,
        identityPub: ByteArray,
        endpoints: List<Endpoint>,
        publishedAtUnixMs: Long,
        ttlMs: Long,
        sequence: Long,
        ed25519PrivateKey: ByteArray,
    ): EndpointRecord {
        val protoEndpoints = endpoints.map {
            ir.vmessenger.core.proto.dht.v1.Endpoint.newBuilder()
                .setTransport(it.transport.value)
                .setAddress(it.address)
                .build()
        }
        val unsigned = EndpointRecord.newBuilder()
            .setIdentityHash(ByteString.copyFrom(identityHash))
            .setIdentityPub(ByteString.copyFrom(identityPub))
            .addAllEndpoints(protoEndpoints)
            .setPublishedAtUnixMs(publishedAtUnixMs)
            .setTtlMs(ttlMs)
            .setSequence(sequence)
            .setTranscriptVersion(EndpointRecordTranscript.TRANSCRIPT_VERSION_V2)
            .build()
        val transcript = unsigned.buildTranscript()
        val signature = cryptoEngine.signEd25519(transcript, ed25519PrivateKey)
        return unsigned.toBuilder()
            .setSignature(ByteString.copyFrom(signature))
            .build()
    }
}
