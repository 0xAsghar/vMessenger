package ir.vmessenger.node

import com.google.protobuf.InvalidProtocolBufferException
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.core.proto.relay.v1.RelayRole
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.slf4j.LoggerFactory
import io.ktor.server.websocket.DefaultWebSocketServerSession as WsSession

/** Entry point of a `/relay` socket: reads the hello and dispatches on its role. */
class RelaySessionHandler(private val state: RelayNodeState) {
    private val log = LoggerFactory.getLogger(RelaySessionHandler::class.java)
    private val listeners = ListenerHandler(state)
    private val dials = DialHandler(state)

    suspend fun handle(session: WsSession, ip: String) {
        try {
            val frame = session.incoming.receive() as? Frame.Binary ?: return
            val hello = RelayHello.parseFrom(frame.readBytes())
            when (hello.role) {
                RelayRole.RELAY_ROLE_LISTENER -> listeners.handle(hello, session, ip)
                RelayRole.RELAY_ROLE_DIALER -> dials.handleDialer(hello, session, ip)
                RelayRole.RELAY_ROLE_ACCEPT -> dials.handleAccept(hello, session, ip)
                else -> RelayWire.reject(
                    session,
                    hello.circuitId,
                    Rejection("Unknown relay role", state.stats.rejectedInvalidHello),
                    ip,
                )
            }
        } catch (_: ClosedReceiveChannelException) {
            // client disconnected
        } catch (e: InvalidProtocolBufferException) {
            state.stats.rejectedInvalidHello.incrementAndGet()
            log.debug("relay_session_error reason=malformed_hello detail=\"{}\"", e.message)
        } catch (e: IllegalStateException) {
            log.debug("relay_session_error reason=\"{}\"", e.message)
        }
    }
}
