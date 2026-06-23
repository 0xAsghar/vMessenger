package ir.vmessenger.network.dht

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import javax.inject.Inject

class EndpointRecordVerifier @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    @Suppress("ReturnCount")
    fun verify(record: EndpointRecord, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (record.identityHash.size() != 32 || record.identityPub.size() != 32) return false
        val computedHash = cryptoEngine.sha256(record.identityPub.toByteArray())
        if (!computedHash.contentEquals(record.identityHash.toByteArray())) return false
        if (nowMs >= record.publishedAtUnixMs + record.ttlMs) return false
        val transcript = record.buildTranscript()
        return cryptoEngine.verifyEd25519(
            transcript,
            record.signature.toByteArray(),
            record.identityPub.toByteArray(),
        )
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
            .build()
        val transcript = unsigned.buildTranscript()
        val signature = cryptoEngine.signEd25519(transcript, ed25519PrivateKey)
        return unsigned.toBuilder()
            .setSignature(ByteString.copyFrom(signature))
            .build()
    }
}
