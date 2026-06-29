package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.RelayPeerPolicy
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.RelayClose
import ir.vmessenger.core.proto.app.v1.RelayData
import ir.vmessenger.core.proto.app.v1.RelayOpen
import ir.vmessenger.core.proto.app.v1.RelayReady
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class RelayCircuit(
    val circuitId: String,
    val upstreamPeerHash: ByteArray,
    val targetIdentityHash: ByteArray?,
    val createdAtUnixMs: Long,
    var lastActivityUnixMs: Long,
    var bytesRelayed: Long = 0,
)

/**
 * Phase 6: relay-capable peer circuit bridging for vMessenger encrypted frames only.
 */
@Singleton
class PeerRelayService @Inject constructor() {
    private val circuits = ConcurrentHashMap<String, RelayCircuit>()
    @Volatile
    var policy: RelayPeerPolicy = RelayPeerPolicy.OFF

    fun isAcceptingCircuits(): Boolean =
        P2PConfig.relayPeerModeEnabled && policy != RelayPeerPolicy.OFF

    fun activeCircuitCount(): Int = circuits.size

    fun totalBytesRelayed(): Long = circuits.values.sumOf { it.bytesRelayed }

    fun pruneExpired(nowMs: Long = System.currentTimeMillis()) {
        circuits.entries.removeIf { (_, circuit) ->
            nowMs - circuit.lastActivityUnixMs > CIRCUIT_IDLE_TTL_MS
        }
        updateTracker()
    }

    fun handleRelayOpen(
        envelope: MessageEnvelope,
        peerHash: ByteArray,
        policyAllowed: Boolean = true,
    ): MessageEnvelope? {
        if (!isAcceptingCircuits() || !policyAllowed) return null
        pruneExpired()
        if (circuits.size >= MAX_CIRCUITS) {
            AppLogger.warn("PeerRelay", "circuit limit reached")
            return null
        }
        val open = envelope.relayOpen
        val circuit = RelayCircuit(
            circuitId = open.circuitId,
            upstreamPeerHash = peerHash,
            targetIdentityHash = open.targetIdentityHash.toByteArray().takeIf { open.targetIdentityHash.size() == 32 },
            createdAtUnixMs = System.currentTimeMillis(),
            lastActivityUnixMs = System.currentTimeMillis(),
        )
        circuits[open.circuitId] = circuit
        updateTracker()
        AppLogger.info("PeerRelay", "circuit opened ${open.circuitId}")
        return MessageEnvelope.newBuilder()
            .setMessageId(envelope.messageId)
            .setSenderIdentityHash(envelope.senderIdentityHash)
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(envelope.counter)
            .setRelayReady(RelayReady.newBuilder().setCircuitId(open.circuitId))
            .build()
    }

    fun circuit(circuitId: String): RelayCircuit? = circuits[circuitId]

    fun handleRelayData(envelope: MessageEnvelope): Boolean {
        val data = envelope.relayData
        val circuit = circuits[data.circuitId] ?: return false
        if (data.encryptedFrame.size() > MAX_FRAME_BYTES) return false
        circuit.lastActivityUnixMs = System.currentTimeMillis()
        circuit.bytesRelayed += data.encryptedFrame.size()
        if (circuit.bytesRelayed > MAX_BYTES_PER_CIRCUIT) {
            circuits.remove(data.circuitId)
            updateTracker()
            return false
        }
        updateTracker()
        return true
    }

    fun handleRelayClose(envelope: MessageEnvelope) {
        val close = envelope.relayClose
        circuits.remove(close.circuitId)
        updateTracker()
        AppLogger.info("PeerRelay", "circuit closed ${close.circuitId}: ${close.reason}")
    }

    fun buildRelayDataFrame(circuitId: String, encryptedFrame: ByteArray): ByteArray? {
        val circuit = circuits[circuitId] ?: return null
        circuit.lastActivityUnixMs = System.currentTimeMillis()
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(com.google.protobuf.ByteString.copyFromUtf8("relay-data-$circuitId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setRelayData(
                RelayData.newBuilder()
                    .setCircuitId(circuitId)
                    .setEncryptedFrame(com.google.protobuf.ByteString.copyFrom(encryptedFrame)),
            )
            .build()
        return Frame.newBuilder()
            .setVersion(1)
            .setType(FrameType.FRAME_TYPE_SECURE)
            .setBody(com.google.protobuf.ByteString.copyFrom(envelope.toByteArray()))
            .build()
            .toByteArray()
    }

    fun closeCircuit(circuitId: String, reason: String): MessageEnvelope =
        MessageEnvelope.newBuilder()
            .setMessageId(com.google.protobuf.ByteString.copyFromUtf8("relay-close-$circuitId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setRelayClose(RelayClose.newBuilder().setCircuitId(circuitId).setReason(reason))
            .build()

    private fun updateTracker() {
        NetworkPathTracker.setRelayCircuitStats(activeCircuitCount(), totalBytesRelayed())
    }

    companion object {
        const val MAX_CIRCUITS = 3
        const val MAX_FRAME_BYTES = 64 * 1024
        const val MAX_BYTES_PER_CIRCUIT = 512 * 1024L
        const val CIRCUIT_IDLE_TTL_MS = 5 * 60 * 1000L
    }
}
