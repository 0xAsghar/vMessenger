package ir.vmessenger.network.messaging

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
    fun buildListenerHello(
        identityHash: ByteArray,
        identityPub: ByteArray,
        ed25519PrivateKey: ByteArray,
    ): RelayHello {
        require(identityHash.size == 32 && identityPub.size == 32)
        val ts = System.currentTimeMillis()
        val transcript = RelayProof.buildListenerProofTranscript(identityHash, ts)
        val proof = cryptoEngine.signEd25519(transcript, ed25519PrivateKey)
        return RelayHello.newBuilder()
            .setRole(RelayRole.RELAY_ROLE_LISTENER)
            .setListenerId(com.google.protobuf.ByteString.copyFrom(identityHash))
            .setIdentityPub(com.google.protobuf.ByteString.copyFrom(identityPub))
            .setProof(com.google.protobuf.ByteString.copyFrom(proof))
            .setTs(ts)
            .build()
    }

    fun buildAcceptHello(circuitId: String): RelayHello =
        RelayHello.newBuilder()
            .setRole(RelayRole.RELAY_ROLE_ACCEPT)
            .setCircuitId(circuitId)
            .setTs(System.currentTimeMillis())
            .build()
}
