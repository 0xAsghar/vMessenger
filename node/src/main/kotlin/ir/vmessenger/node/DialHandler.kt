package ir.vmessenger.node

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.proto.relay.v1.RelayEventType
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.node.RelayWire.sendEvent
import ir.vmessenger.node.RelayWire.trySendEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import io.ktor.server.websocket.DefaultWebSocketServerSession as WsSession

/**
 * DIALER and ACCEPT hellos.
 *
 * A dialer names a listener; the relay tells that listener about the new
 * circuit and parks the dialer in [RelayNodeState.pendingDialers] until the
 * listener opens an ACCEPT socket for the circuit (or the dialer gives up, or
 * [NodeConfig.pendingDialerTtlMs] elapses). Once accepted both sockets get a
 * READY event and binary frames are pumped between them until either side
 * closes or the circuit idles for [NodeConfig.circuitIdleTimeoutMs].
 */
class DialHandler(private val state: RelayNodeState) {
    private val log = LoggerFactory.getLogger(DialHandler::class.java)
    private val cfg = state.config
    private val stats = state.stats

    private enum class Outcome { ACCEPTED, DIALER_CLOSED, TIMED_OUT, UNREACHABLE }

    suspend fun handleDialer(hello: RelayHello, session: WsSession, ip: String) {
        val targetId = hello.targetId.toByteArray()
        val listener = targetId.takeIf { it.size == HASH_SIZE }
            ?.let { state.listeners[IdentityHashMatcher.routingKeyHex(it)] }
        val rejection = checkDialer(ip, targetId, listener)
        if (rejection != null || listener == null) {
            RelayWire.reject(session, hello.circuitId, rejection ?: notListening(), ip)
            return
        }
        val pending = PendingDialer(session, listener, hello.circuitId.ifBlank { UUID.randomUUID().toString() })
        val waiting = listener.pending.incrementAndGet()
        try {
            if (waiting > cfg.maxPendingPerListener) {
                RelayWire.reject(session, pending.circuitId, Rejection("Listener busy", stats.rejectedListenerBusy), ip)
            } else {
                dial(pending, ip)
            }
        } finally {
            listener.pending.decrementAndGet()
        }
    }

    private fun checkDialer(ip: String, targetId: ByteArray, listener: ListenerEntry?): Rejection? = when {
        !state.dialLimiter.tryAcquire(ip) -> Rejection("Rate limited", stats.rejectedRateLimited)
        targetId.size != HASH_SIZE -> Rejection("Invalid target_id", stats.rejectedInvalidHello)
        listener == null -> notListening()
        state.pendingDialers.size >= cfg.maxPendingDialers -> Rejection("Relay busy", stats.rejectedRelayFull)
        else -> null
    }

    private suspend fun dial(pending: PendingDialer, ip: String) {
        val id = pending.circuitId
        // A client-chosen circuit_id that is already pending must not displace the
        // earlier dialer: that dialer would never see its accept and would wait on
        // `finished` forever, leaking both sockets and the listener's pending slot.
        if (state.pendingDialers.putIfAbsent(id, pending) != null) {
            RelayWire.reject(pending.session, id, Rejection("Duplicate circuit_id", stats.rejectedInvalidHello), ip)
            return
        }
        stats.pendingDialers.set(state.pendingDialers.size)
        try {
            val incoming = RelayWire.event(RelayEventType.RELAY_EVENT_TYPE_INCOMING, id)
            val delivered = pending.listener.session.trySendEvent(incoming)
            val outcome = if (delivered) awaitAccept(pending) else Outcome.UNREACHABLE
            log.info("dial target={} circuit={} result={}", pending.listener.prefix, id, outcome)
            when (outcome) {
                Outcome.TIMED_OUT -> RelayWire.reject(pending.session, id, Rejection("Relay accept timed out"), ip)
                Outcome.UNREACHABLE -> RelayWire.reject(pending.session, id, notListening(), ip)
                Outcome.ACCEPTED, Outcome.DIALER_CLOSED -> Unit
            }
        } finally {
            state.pendingDialers.remove(id, pending)
            stats.pendingDialers.set(state.pendingDialers.size)
        }
    }

    /**
     * Waits for the circuit to be accepted (and then torn down), the dialer to
     * hang up, or the TTL to pass. When an ACCEPT races the timeout or hang-up
     * the accept side already owns the circuit, so the dialer keeps waiting for it.
     *
     * This relies on [dial] registering `pending` with `putIfAbsent`: while the
     * dialer waits, its entry can only be removed by [handleAccept], so a failed
     * conditional remove here always means an accept is in flight.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitAccept(pending: PendingDialer): Outcome {
        val outcome = select {
            pending.finished.onAwait { Outcome.ACCEPTED }
            pending.session.closeReason.onAwait { Outcome.DIALER_CLOSED }
            onTimeout(cfg.pendingDialerTtlMs) { Outcome.TIMED_OUT }
        }
        if (outcome == Outcome.ACCEPTED || state.pendingDialers.remove(pending.circuitId, pending)) return outcome
        pending.finished.await()
        return Outcome.ACCEPTED
    }

    suspend fun handleAccept(hello: RelayHello, session: WsSession, ip: String) {
        val id = hello.circuitId
        val pending = if (id.isBlank()) null else state.pendingDialers.remove(id)
        if (pending == null) {
            val message = if (id.isBlank()) "Missing circuit_id" else "Unknown or expired circuit"
            RelayWire.reject(session, id, Rejection(message, stats.rejectedInvalidHello), ip)
            return
        }
        stats.pendingDialers.set(state.pendingDialers.size)
        try {
            if (stats.activeCircuits.incrementAndGet() > cfg.maxCircuits) {
                RelayWire.reject(pending.session, id, Rejection("Relay full", stats.rejectedRelayFull), ip)
                RelayWire.sendError(session, id, "Relay full")
            } else {
                runCircuit(pending, session)
            }
        } finally {
            stats.activeCircuits.decrementAndGet()
            pending.finished.complete(Unit)
        }
    }

    private suspend fun runCircuit(pending: PendingDialer, acceptor: WsSession) {
        val id = pending.circuitId
        val dialer = pending.session
        val ready = RelayWire.event(RelayEventType.RELAY_EVENT_TYPE_READY, id)
        dialer.sendEvent(ready)
        acceptor.sendEvent(ready)
        log.info(
            "circuit_opened circuit={} target={} active={}",
            id,
            pending.listener.prefix,
            stats.activeCircuits.get(),
        )
        val bytes = AtomicLong()
        try {
            coroutineScope {
                launch { pump(dialer, acceptor, bytes) }
                launch { pump(acceptor, dialer, bytes) }
            }
        } finally {
            log.info("circuit_closed circuit={} bytes={}", id, bytes.get())
        }
    }

    /** Forwards binary frames from [from] to [to] until either side closes or the direction idles out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun pump(from: WsSession, to: WsSession, bytes: AtomicLong) {
        var open = true
        while (open) {
            val received = select<ChannelResult<Frame>?> {
                from.incoming.onReceiveCatching { it }
                onTimeout(cfg.circuitIdleTimeoutMs) { null }
            }
            open = try {
                if (received == null) closeIdle(from, to) else forward(received.getOrNull(), to, bytes)
            } catch (_: IllegalStateException) {
                // The destination hung up mid-send; release the source too.
                from.close(CloseReason(CloseReason.Codes.GOING_AWAY, "peer gone"))
                false
            }
        }
    }

    private suspend fun forward(frame: Frame?, to: WsSession, bytes: AtomicLong): Boolean = when (frame) {
        is Frame.Binary -> {
            val data = frame.readBytes()
            bytes.addAndGet(data.size.toLong())
            to.outgoing.send(Frame.Binary(true, data))
            true
        }
        null, is Frame.Close -> {
            to.close(CloseReason(CloseReason.Codes.NORMAL, "peer closed"))
            false
        }
        else -> true
    }

    private suspend fun closeIdle(from: WsSession, to: WsSession): Boolean {
        val idle = CloseReason(CloseReason.Codes.GOING_AWAY, "idle")
        from.close(idle)
        to.close(idle)
        return false
    }

    private fun notListening() = Rejection("Peer not listening on relay")

    private companion object {
        const val HASH_SIZE = 32
    }
}
