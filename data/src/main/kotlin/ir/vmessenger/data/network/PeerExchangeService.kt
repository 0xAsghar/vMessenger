package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.NetworkNodeList
import ir.vmessenger.core.proto.app.v1.NodeRole
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exchanges network-node hints with a connected peer after the secure handshake
 * completes (docs/P2P-Phases.md Phase 4). Signed records are preferred when keys
 * are available; legacy address strings remain for backward compatibility.
 */
@Singleton
class PeerExchangeService @Inject constructor(
    private val networkNodeRepository: NetworkNodeRepository,
    private val signedNodeRecordSigner: SignedNodeRecordSigner,
    private val signedNodeRecordVerifier: SignedNodeRecordVerifier,
    private val identityRepository: IdentityRepository,
) {
    suspend fun exchangeOnSession(session: ActiveSecureSession, self: PeerIdentity) {
        if (!P2PConfig.peerExchangeEnabled) return
        val (bootstrap, relay) = networkNodeRepository.healthyNodesForExchange(MAX_NODES)
        val signed = buildSignedRecords(bootstrap, relay, self)
        if (bootstrap.isEmpty() && relay.isEmpty() && signed.isEmpty()) return
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("node-exchange-${System.currentTimeMillis()}"))
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setNetworkNodes(
                NetworkNodeList.newBuilder()
                    .addAllBootstrapAddresses(bootstrap)
                    .addAllRelayAddresses(relay)
                    .addAllSignedRecords(signed),
            )
            .build()
        runCatching {
            val sealed = session.seal(envelope.toByteArray())
            val frame = Frame.newBuilder()
                .setVersion(1)
                .setType(FrameType.FRAME_TYPE_SECURE)
                .setBody(ByteString.copyFrom(sealed))
                .build()
            session.writeFrame(frame.toByteArray())
            AppLogger.info(
                "PeerExchange",
                "sent ${bootstrap.size} bootstrap + ${relay.size} relay + ${signed.size} signed",
            )
        }.onFailure {
            AppLogger.warn("PeerExchange", "send failed: ${it.message}")
        }
    }

    suspend fun ingestFromEnvelope(envelope: MessageEnvelope) {
        if (!envelope.hasNetworkNodes()) return
        val nodes = envelope.networkNodes
        if (nodes.signedRecordsCount > 0) {
            networkNodeRepository.importSignedNodeRecords(
                nodes.signedRecordsList,
                signedNodeRecordVerifier,
            )
        }
        networkNodeRepository.importExchangedNodes(
            bootstrapAddresses = nodes.bootstrapAddressesList,
            relayAddresses = nodes.relayAddressesList,
        )
        AppLogger.info(
            "PeerExchange",
            "learned ${nodes.bootstrapAddressesCount} bootstrap + ${nodes.relayAddressesCount} relay + " +
                "${nodes.signedRecordsCount} signed from peer",
        )
    }

    private suspend fun buildSignedRecords(
        bootstrap: List<String>,
        relay: List<String>,
        self: PeerIdentity,
    ): List<ir.vmessenger.core.proto.app.v1.SignedNodeRecord> {
        val privateKey = self.ed25519PrivateKey ?: identityRepository.getEd25519PrivateKey() ?: return emptyList()
        val publicKey = self.ed25519PublicKey
        val expires = System.currentTimeMillis() + RECORD_TTL_MS
        return buildList {
            bootstrap.take(5).forEach { address ->
                add(
                    signedNodeRecordSigner.sign(
                        address = address,
                        role = NodeRole.NODE_ROLE_BOOTSTRAP,
                        publicKey = publicKey,
                        capabilities = listOf("bootstrap"),
                        expiresAtUnixMs = expires,
                        ed25519PrivateKey = privateKey,
                    ),
                )
            }
            relay.take(5).forEach { address ->
                add(
                    signedNodeRecordSigner.sign(
                        address = address,
                        role = NodeRole.NODE_ROLE_RELAY,
                        publicKey = publicKey,
                        capabilities = listOf("relay"),
                        expiresAtUnixMs = expires,
                        ed25519PrivateKey = privateKey,
                    ),
                )
            }
        }
    }

    companion object {
        private const val MAX_NODES = 20
        private const val RECORD_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
