package ir.vmessenger.core.common.network

import ir.vmessenger.core.common.network.Canonical.lp
import ir.vmessenger.core.common.network.Canonical.u64be

/**
 * Bytes a relay listener signs with its identity key to prove the socket belongs to the identity it
 * registers as. Shared by the app (`RelayHelloFactory`) and the relay node (`ListenerHandler`).
 */
object RelayProof {
    /** Value of `RelayHello.proof_version` for the v2 transcript. */
    const val PROOF_VERSION_V2 = 2

    private val TAG_V2 = "vmessenger-relay-listener-v2".toByteArray(Charsets.UTF_8)
    private val TAG_LEGACY = "vmessenger-relay-listener".toByteArray(Charsets.UTF_8)

    /** v2: `"vmessenger-relay-listener-v2" || lp(listener_id) || lp(identity_pub) || u64be(ts)`. */
    fun buildListenerProofTranscript(listenerId: ByteArray, identityPub: ByteArray, ts: Long): ByteArray =
        TAG_V2 + lp(listenerId) + lp(identityPub) + u64be(ts)

    /**
     * 0.x transcript (`proof_version` 0/1): tag || listener_id || decimal ts. Only the relay node still
     * verifies it, so pre-v2 apps keep their listener during the transition; the app never signs it.
     */
    fun buildLegacyListenerProofTranscript(listenerId: ByteArray, ts: Long): ByteArray =
        TAG_LEGACY + listenerId + ts.toString().toByteArray(Charsets.UTF_8)
}
