package ir.vmessenger.core.common.network

import ir.vmessenger.core.common.network.Canonical.lp
import ir.vmessenger.core.common.network.Canonical.lpUtf8
import ir.vmessenger.core.common.network.Canonical.u32be
import ir.vmessenger.core.common.network.Canonical.u64be

/**
 * Signed bytes of a DHT `EndpointRecord`, expressed over primitives so the app (`network:dht`) and the
 * relay node (`NodeEndpointRecordVerifier`) share one definition without sharing generated protobuf.
 */
object EndpointRecordTranscript {
    /** Value of `EndpointRecord.transcript_version` for the v2 transcript. */
    const val TRANSCRIPT_VERSION_V2 = 2

    private val TAG_V2 = "vmessenger-endpoint-record-v2".toByteArray(Charsets.UTF_8)

    /** One `(transport, address)` pair of the record. */
    data class Entry(val transport: String, val address: String)

    /**
     * v2: `tag || lp(identity_hash) || lp(identity_pub) || u32be(n) || (lpUtf8(transport) || lpUtf8(address))*
     * sorted by (transport, address) || u64be(published_at) || u64be(ttl) || u64be(sequence)`.
     */
    @Suppress("LongParameterList") // one parameter per signed field
    fun buildV2(
        identityHash: ByteArray,
        identityPub: ByteArray,
        endpoints: List<Entry>,
        publishedAtUnixMs: Long,
        ttlMs: Long,
        sequence: Long,
    ): ByteArray {
        val sorted = endpoints.sortedWith(compareBy({ it.transport }, { it.address }))
        var out = TAG_V2 + lp(identityHash) + lp(identityPub) + u32be(sorted.size)
        for (entry in sorted) {
            out += lpUtf8(entry.transport) + lpUtf8(entry.address)
        }
        return out + u64be(publishedAtUnixMs) + u64be(ttlMs) + u64be(sequence)
    }

    /**
     * 0.x transcript (`transcript_version` 0): identity hash || identity pub || sorted `transport\taddress`
     * lines || decimal published_at || ttl || sequence. Only the relay node still accepts it.
     */
    @Suppress("LongParameterList") // one parameter per signed field
    fun buildLegacy(
        identityHash: ByteArray,
        identityPub: ByteArray,
        endpoints: List<Entry>,
        publishedAtUnixMs: Long,
        ttlMs: Long,
        sequence: Long,
    ): ByteArray {
        val endpointsBytes = endpoints
            .sortedBy { it.transport + it.address }
            .joinToString("\n") { "${it.transport}\t${it.address}" }
            .toByteArray(Charsets.UTF_8)
        return identityHash +
            identityPub +
            endpointsBytes +
            publishedAtUnixMs.toString().toByteArray(Charsets.UTF_8) +
            ttlMs.toString().toByteArray(Charsets.UTF_8) +
            sequence.toString().toByteArray(Charsets.UTF_8)
    }
}
