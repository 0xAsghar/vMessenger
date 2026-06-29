package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.app.v1.NodeRole
import ir.vmessenger.core.proto.app.v1.SignedNodeRecord
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignedNodeRecordVerifier @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    fun verify(record: SignedNodeRecord, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (record.publicKey.size() != 32) return false
        if (record.expiresAtUnixMs <= nowMs) return false
        if (record.address.isBlank()) return false
        val transcript = buildTranscript(record)
        return cryptoEngine.verifyEd25519(
            transcript,
            record.signature.toByteArray(),
            record.publicKey.toByteArray(),
        )
    }

    fun buildTranscript(record: SignedNodeRecord): ByteArray {
        val role = when (record.role) {
            NodeRole.NODE_ROLE_BOOTSTRAP -> "bootstrap"
            NodeRole.NODE_ROLE_RELAY -> "relay"
            else -> "unknown"
        }
        val caps = record.capabilitiesList.joinToString(",")
        return "${record.address}|$role|${record.expiresAtUnixMs}|$caps".toByteArray(Charsets.UTF_8)
    }
}

@Singleton
class SignedNodeRecordSigner @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val verifier: SignedNodeRecordVerifier,
) {
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
            .build()
        val signature = cryptoEngine.signEd25519(verifier.buildTranscript(unsigned), ed25519PrivateKey)
        return unsigned.toBuilder()
            .setSignature(ByteString.copyFrom(signature))
            .build()
    }
}
