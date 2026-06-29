package ir.vmessenger.network.messaging

import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.RelayOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PeerRelayServiceTest {
    private val service = PeerRelayService()

    @Test
    fun opensCircuitWhenEnabled() {
        service.policy = ir.vmessenger.core.common.network.RelayPeerPolicy.CONTACTS_ONLY
        ir.vmessenger.core.common.network.P2PConfig.relayPeerModeEnabled = true
        val peerHash = ByteArray(32) { 1 }
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(com.google.protobuf.ByteString.copyFromUtf8("open-1"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setRelayOpen(
                RelayOpen.newBuilder()
                    .setCircuitId("circuit-1")
                    .setTargetIdentityHash(com.google.protobuf.ByteString.copyFrom(ByteArray(32) { 2 })),
            )
            .build()
        val ready = service.handleRelayOpen(envelope, peerHash)
        assertNotNull(ready)
        assertEquals(1, service.activeCircuitCount())
        ir.vmessenger.core.common.network.P2PConfig.resetToDefaults()
    }

    @Test
    fun rejectsOversizedRelayData() {
        ir.vmessenger.core.common.network.P2PConfig.relayPeerModeEnabled = true
        service.policy = ir.vmessenger.core.common.network.RelayPeerPolicy.CONTACTS_ONLY
        val peerHash = ByteArray(32)
        val open = MessageEnvelope.newBuilder()
            .setRelayOpen(RelayOpen.newBuilder().setCircuitId("c1"))
            .build()
        service.handleRelayOpen(open, peerHash)
        val huge = ByteArray(PeerRelayService.MAX_FRAME_BYTES + 1)
        val data = MessageEnvelope.newBuilder()
            .setRelayData(
                ir.vmessenger.core.proto.app.v1.RelayData.newBuilder()
                    .setCircuitId("c1")
                    .setEncryptedFrame(com.google.protobuf.ByteString.copyFrom(huge)),
            )
            .build()
        assertEquals(false, service.handleRelayData(data))
        ir.vmessenger.core.common.network.P2PConfig.resetToDefaults()
    }
}
