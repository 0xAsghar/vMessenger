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
        val transcript = record.buildTranscript()
        return sodium.cryptoSignVerifyDetached(
            record.signature.toByteArray(),
            transcript,
            transcript.size,
            identityPub,
        )
    }
}

private fun EndpointRecord.buildTranscript(): ByteArray {
    val endpointsBytes = endpointsList
        .sortedBy { it.transport + it.address }
        .joinToString("\n") { "${it.transport}\t${it.address}" }
        .toByteArray(Charsets.UTF_8)
    return identityHash.toByteArray() +
        identityPub.toByteArray() +
        endpointsBytes +
        publishedAtUnixMs.toString().toByteArray(Charsets.UTF_8) +
        ttlMs.toString().toByteArray(Charsets.UTF_8) +
        sequence.toString().toByteArray(Charsets.UTF_8)
}
