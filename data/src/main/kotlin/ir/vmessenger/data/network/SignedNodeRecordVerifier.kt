package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.network.Canonical.lp
import ir.vmessenger.core.common.network.Canonical.lpUtf8
import ir.vmessenger.core.common.network.Canonical.u32be
import ir.vmessenger.core.common.network.Canonical.u64be
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NodeTrust
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.app.v1.NodeRole
import ir.vmessenger.core.proto.app.v1.SignedNodeRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies peer-exchanged node records. Format v2: signature over
 * `"vmessenger-node-record-v2" || lpUtf8(address) || u32be(role) || lp(public_key) || u32be(n) || lpUtf8(cap)*
 * || u64be(expires_at)`; `transcript_version` must be 2.
 *
 * A valid record signed by the operator key ([NetworkConfig.OPERATOR_ED25519_PUBLIC_KEY_HEX])
 * yields [NodeTrust.OFFICIAL]; any other valid self-signed record is [NodeTrust.COMMUNITY]
 * and is never auto-enabled.
 */
@Singleton
class SignedNodeRecordVerifier(
    private val cryptoEngine: CryptoEngine,
    private val operatorPublicKey: ByteArray?,
) {
    @Inject
    constructor(cryptoEngine: CryptoEngine) : this(cryptoEngine, NetworkConfig.operatorEd25519PublicKey())

    /** Returns the trust level of a valid record, or null when the record is invalid or expired. */
    fun verify(record: SignedNodeRecord, nowMs: Long = System.currentTimeMillis()): NodeTrust? {
        if (!isValid(record, nowMs)) return null
        val official = operatorPublicKey != null && record.publicKey.toByteArray().contentEquals(operatorPublicKey)
        return if (official) NodeTrust.OFFICIAL else NodeTrust.COMMUNITY
    }

    @Suppress("ReturnCount") // early-exit validation chain
    private fun isValid(record: SignedNodeRecord, nowMs: Long): Boolean {
        if (record.transcriptVersion != TRANSCRIPT_VERSION) return false
        if (record.publicKey.size() != PUBLIC_KEY_BYTES) return false
        if (record.expiresAtUnixMs <= nowMs) return false
        if (record.address.isBlank()) return false
        // Peer-supplied fields must never turn into an exception on the inbound path.
        val transcript = runCatching { buildTranscript(record) }.getOrNull() ?: return false
        return cryptoEngine.verifyEd25519(
            transcript,
            record.signature.toByteArray(),
            record.publicKey.toByteArray(),
        )
    }

    fun buildTranscript(record: SignedNodeRecord): ByteArray {
        var out = TAG_V2 +
            lpUtf8(record.address) +
            u32be(record.roleValue) +
            lp(record.publicKey.toByteArray()) +
            u32be(record.capabilitiesCount)
        for (capability in record.capabilitiesList) {
            out += lpUtf8(capability)
        }
        return out + u64be(record.expiresAtUnixMs)
    }

    companion object {
        const val TRANSCRIPT_VERSION = 2
        private const val PUBLIC_KEY_BYTES = 32
        private val TAG_V2 = "vmessenger-node-record-v2".toByteArray(Charsets.UTF_8)
    }
}

@Singleton
class SignedNodeRecordSigner @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val verifier: SignedNodeRecordVerifier,
) {
    @Suppress("LongParameterList") // one parameter per signed field
    fun sign(
        address: String,
        role: NodeRole,
        publicKey: ByteArray,
        capabilities: List<String>,
        expiresAtUnixMs: Long,
        ed25519PrivateKey: ByteArray,
    ): SignedNodeRecord {
        val unsigned = SignedNodeRecord.newBuilder()
            .setAddress(address)
            .setRole(role)
            .setPublicKey(ByteString.copyFrom(publicKey))
            .addAllCapabilities(capabilities)
            .setExpiresAtUnixMs(expiresAtUnixMs)
            .setTranscriptVersion(SignedNodeRecordVerifier.TRANSCRIPT_VERSION)
            .build()
        val signature = cryptoEngine.signEd25519(verifier.buildTranscript(unsigned), ed25519PrivateKey)
        return unsigned.toBuilder()
            .setSignature(ByteString.copyFrom(signature))
            .build()
    }
}
