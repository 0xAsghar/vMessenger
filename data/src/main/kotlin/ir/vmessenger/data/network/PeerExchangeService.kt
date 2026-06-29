package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.NetworkNodeList
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exchanges signed network-node hints with a connected peer after the secure
 * handshake completes (docs/P2P-Phases.md Phase 4).
 */
@Singleton
class PeerExchangeService @Inject constructor(
    private val networkNodeRepository: NetworkNodeRepository,
) {
    suspend fun exchangeOnSession(session: ActiveSecureSession, self: PeerIdentity) {
        if (!P2PConfig.peerExchangeEnabled) return
        val (bootstrap, relay) = networkNodeRepository.healthyNodesForExchange(MAX_NODES)
        if (bootstrap.isEmpty() && relay.isEmpty()) return
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("node-exchange-${System.currentTimeMillis()}"))
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setNetworkNodes(
                NetworkNodeList.newBuilder()
                    .addAllBootstrapAddresses(bootstrap)
                    .addAllRelayAddresses(relay),
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
            AppLogger.info("PeerExchange", "sent ${bootstrap.size} bootstrap + ${relay.size} relay hints")
        }.onFailure {
            AppLogger.warn("PeerExchange", "send failed: ${it.message}")
        }
    }

    suspend fun ingestFromEnvelope(envelope: MessageEnvelope) {
        if (!envelope.hasNetworkNodes()) return
        val nodes = envelope.networkNodes
        networkNodeRepository.importExchangedNodes(
            bootstrapAddresses = nodes.bootstrapAddressesList,
            relayAddresses = nodes.relayAddressesList,
        )
        AppLogger.info(
            "PeerExchange",
            "learned ${nodes.bootstrapAddressesCount} bootstrap + ${nodes.relayAddressesCount} relay from peer",
        )
    }

    companion object {
        private const val MAX_NODES = 20
    }
}
