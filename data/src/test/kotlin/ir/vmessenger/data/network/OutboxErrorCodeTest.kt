package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What lands in `outbox.lastError` (and so in `ChatMessage.lastError`) must be a
 * stable code, never a developer message the UI would have to string-match.
 */
class OutboxErrorCodeTest {
    @Test
    fun protocolMismatchIsItsOwnCode() {
        val error = AppError.ProtocolVersion("peer speaks major 1", peerMajor = 1)

        assertEquals(OutboxError.PEER_PROTOCOL_OUTDATED, error.toOutboxErrorCode())
    }

    @Test
    fun keyChangeAndDiscoveryFailuresAreDistinguished() {
        assertEquals(OutboxError.PEER_KEY_CHANGED, AppError.Security("key changed").toOutboxErrorCode())
        assertEquals(OutboxError.ENDPOINT_NOT_FOUND, AppError.NotFound("endpoint").toOutboxErrorCode())
        assertEquals(OutboxError.NETWORK_UNAVAILABLE, AppError.Network("no route").toOutboxErrorCode())
    }

    @Test
    fun anythingElseFallsBackToSendFailed() {
        assertEquals(OutboxError.SEND_FAILED, AppError.Unknown("boom").toOutboxErrorCode())
        assertEquals(OutboxError.SEND_FAILED, AppError.Validation("bad").toOutboxErrorCode())
    }
}
