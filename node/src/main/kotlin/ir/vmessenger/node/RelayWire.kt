package ir.vmessenger.node

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import ir.vmessenger.core.proto.relay.v1.RelayEvent
import ir.vmessenger.core.proto.relay.v1.RelayEventType
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import io.ktor.server.websocket.DefaultWebSocketServerSession as WsSession

/**
 * Why a relay hello was turned away: the message sent to the client (some are
 * matched literally by the app, e.g. `Peer not listening on relay`) and the
 * [NodeStats] counter to bump, if any.
 */
class Rejection(val message: String, val counter: AtomicLong? = null)

/** Encoding of relay control events plus the shared "send error and close" path. */
internal object RelayWire {
    private val log = LoggerFactory.getLogger(RelayWire::class.java)

    fun event(type: RelayEventType, circuitId: String, message: String = ""): ByteArray =
        RelayEvent.newBuilder()
            .setType(type)
            .setCircuitId(circuitId)
            .setMessage(message)
            .build()
            .toByteArray()

    suspend fun WsSession.sendEvent(bytes: ByteArray) {
        outgoing.send(Frame.Binary(true, bytes))
    }

    /** Sends [bytes]; returns false instead of throwing when the peer is already gone. */
    suspend fun WsSession.trySendEvent(bytes: ByteArray): Boolean = try {
        sendEvent(bytes)
        true
    } catch (_: IllegalStateException) {
        false
    }

    /** Best-effort ERROR event followed by a policy close; never throws for a dead peer. */
    suspend fun sendError(session: WsSession, circuitId: String, message: String) {
        session.trySendEvent(event(RelayEventType.RELAY_EVENT_TYPE_ERROR, circuitId, message))
        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, message))
    }

    /** Counts, logs (client address only at DEBUG) and answers a [Rejection]. */
    suspend fun reject(session: WsSession, circuitId: String, rejection: Rejection, ip: String) {
        rejection.counter?.incrementAndGet()
        if (log.isDebugEnabled) {
            log.debug("rejected reason=\"{}\" ip={}", rejection.message, ip)
        } else {
            log.info("rejected reason=\"{}\"", rejection.message)
        }
        sendError(session, circuitId, rejection.message)
    }
}
