package ir.vmessenger.node

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.EndpointRecordTranscript
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import java.security.MessageDigest

/**
 * Verifies publisher signatures on stored records. Accepts `transcript_version` 2 (the v2
 * domain-separated transcript every 1.0 app signs) and 0 (the 0.x transcript) so devices on either
 * side of the upgrade keep publishing during the transition; anything else is rejected.
 */
class NodeEndpointRecordVerifier {
    private val sodium = LazySodiumJava(SodiumJava())

    @Suppress("ReturnCount")
    fun verify(record: EndpointRecord, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (record.identityHash.size() != HASH_SIZE || record.identityPub.size() != HASH_SIZE) return false
        val identityPub = record.identityPub.toByteArray()
        val computedHash = MessageDigest.getInstance("SHA-256").digest(identityPub)
        if (!computedHash.contentEquals(record.identityHash.toByteArray())) return false
        if (nowMs >= record.publishedAtUnixMs + record.ttlMs) return false
        val transcript = buildTranscript(record) ?: return false
        return sodium.cryptoSignVerifyDetached(
            record.signature.toByteArray(),
            transcript,
            transcript.size,
            identityPub,
        )
    }

    companion object {
        const val HASH_SIZE = 32
        const val TRANSCRIPT_VERSION_LEGACY = 0

        /** Signed bytes for the record's `transcript_version`, or null when that version is not supported. */
        fun buildTranscript(record: EndpointRecord): ByteArray? {
            val entries = record.endpointsList.map { EndpointRecordTranscript.Entry(it.transport, it.address) }
            return when (record.transcriptVersion) {
                EndpointRecordTranscript.TRANSCRIPT_VERSION_V2 -> EndpointRecordTranscript.buildV2(
                    identityHash = record.identityHash.toByteArray(),
                    identityPub = record.identityPub.toByteArray(),
                    endpoints = entries,
                    publishedAtUnixMs = record.publishedAtUnixMs,
                    ttlMs = record.ttlMs,
                    sequence = record.sequence,
                )
                TRANSCRIPT_VERSION_LEGACY -> EndpointRecordTranscript.buildLegacy(
                    identityHash = record.identityHash.toByteArray(),
                    identityPub = record.identityPub.toByteArray(),
                    endpoints = entries,
                    publishedAtUnixMs = record.publishedAtUnixMs,
                    ttlMs = record.ttlMs,
                    sequence = record.sequence,
                )
                else -> null
            }
        }
    }
}
