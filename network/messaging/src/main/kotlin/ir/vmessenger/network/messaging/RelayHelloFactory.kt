package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.network.RelayProof
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.core.proto.relay.v1.RelayRole
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayHelloFactory @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    /** LISTENER hello with a v2 proof (`proof_version = 2`) over [RelayProof.buildListenerProofTranscript]. */
    fun buildListenerHello(
        identityHash: ByteArray,
        identityPub: ByteArray,
        ed25519PrivateKey: ByteArray,
    ): RelayHello {
        require(identityHash.size == HASH_SIZE && identityPub.size == HASH_SIZE)
        val ts = System.currentTimeMillis()
        val transcript = RelayProof.buildListenerProofTranscript(identityHash, identityPub, ts)
        val proof = cryptoEngine.signEd25519(transcript, ed25519PrivateKey)
        return RelayHello.newBuilder()
            .setRole(RelayRole.RELAY_ROLE_LISTENER)
            .setListenerId(ByteString.copyFrom(identityHash))
            .setIdentityPub(ByteString.copyFrom(identityPub))
            .setProof(ByteString.copyFrom(proof))
            .setTs(ts)
            .setProofVersion(RelayProof.PROOF_VERSION_V2)
            .build()
    }

    fun buildAcceptHello(circuitId: String): RelayHello =
        RelayHello.newBuilder()
            .setRole(RelayRole.RELAY_ROLE_ACCEPT)
            .setCircuitId(circuitId)
            .setTs(System.currentTimeMillis())
            .build()

    private companion object {
        const val HASH_SIZE = 32
    }
}
