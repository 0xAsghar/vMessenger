package ir.vmessenger.node

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import java.security.MessageDigest

class NodeEndpointRecordVerifier {
    private val sodium = LazySodiumJava(SodiumJava())

    @Suppress("ReturnCount")
    fun verify(record: EndpointRecord, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (record.identityHash.size() != 32 || record.identityPub.size() != 32) return false
        val identityPub = record.identityPub.toByteArray()
        val computedHash = MessageDigest.getInstance("SHA-256").digest(identityPub)
        if (!computedHash.contentEquals(record.identityHash.toByteArray())) return false
        if (nowMs >= record.publishedAtUnixMs + record.ttlMs) return false
        val transcript = buildTranscript(record)
        return sodium.cryptoSignVerifyDetached(
            record.signature.toByteArray(),
            transcript,
            transcript.size,
            identityPub,
        )
    }

    companion object {
        /**
         * Bytes the publisher signs: identity hash || identity pub || sorted
         * `transport\taddress` lines || published_at || ttl || sequence (decimal).
         * Must stay byte-identical to the app's EndpointRecord signing transcript.
         */
        fun buildTranscript(record: EndpointRecord): ByteArray {
            val endpointsBytes = record.endpointsList
                .sortedBy { it.transport + it.address }
                .joinToString("\n") { "${it.transport}\t${it.address}" }
                .toByteArray(Charsets.UTF_8)
            return record.identityHash.toByteArray() +
                record.identityPub.toByteArray() +
                endpointsBytes +
                record.publishedAtUnixMs.toString().toByteArray(Charsets.UTF_8) +
                record.ttlMs.toString().toByteArray(Charsets.UTF_8) +
                record.sequence.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
