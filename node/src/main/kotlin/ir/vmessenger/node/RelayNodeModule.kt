package ir.vmessenger.node

import com.google.protobuf.InvalidProtocolBufferException
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import ir.vmessenger.core.proto.dht.v1.StoreResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import io.ktor.server.websocket.DefaultWebSocketServerSession as WsSession

private val log = LoggerFactory.getLogger("ir.vmessenger.node.RelayNodeModule")

private const val DHT_SWEEP_PERIOD_MS = 60_000L
private const val LIMITER_SWEEP_PERIOD_MS = 600_000L
private const val MS_PER_SECOND = 1000L

/**
 * The node's Ktor module: `/healthz`, the one-shot `/dht` RPC socket and the
 * `/relay` control socket, plus the periodic sweeps. Installed by
 * [RelayNodeServer] in production and by `testApplication` in tests.
 */
fun Application.relayNodeModule(state: RelayNodeState) {
    val cfg = state.config
    install(WebSockets) {
        maxFrameSize = cfg.wsMaxFrameBytes
        pingPeriodMillis = cfg.wsPingPeriodMs
        timeoutMillis = cfg.wsTimeoutMs
    }
    val relay = RelaySessionHandler(state)
    routing {
        get("/healthz") { respondHealth(call, state) }
        webSocket("/dht") { handleDht(this, state) }
        webSocket("/relay") { relay.handle(this, call.clientIp(cfg.trustProxy)) }
    }
    startMaintenance(state)
}

private suspend fun respondHealth(call: ApplicationCall, state: RelayNodeState) {
    if (call.request.queryParameters["verbose"].isNullOrEmpty()) {
        call.respondText("ok", ContentType.Text.Plain)
    } else {
        call.respondText(verboseHealthJson(state), ContentType.Application.Json)
    }
}

/** [NodeStats.toJson] extended with uptime in seconds and the (secret-free) config summary. */
internal fun verboseHealthJson(state: RelayNodeState): String {
    val stats = state.stats.toJson().removeSuffix("}")
    val config = state.config.describe().replace("\\", "\\\\").replace("\"", "\\\"")
    return "$stats,\"uptimeSec\":${state.stats.uptimeMs() / MS_PER_SECOND}," +
        "\"nodeId\":\"${IdentityHashMatcher.hashPrefixHex(state.nodeId, state.nodeId.size)}\"," +
        "\"config\":\"$config\"}"
}

private suspend fun handleDht(session: WsSession, state: RelayNodeState) {
    val ip = session.call.clientIp(state.config.trustProxy)
    try {
        val frame = session.incoming.receive() as? Frame.Binary ?: return
        val request = DhtRpcRequest.parseFrom(frame.readBytes())
        val response = if (request.hasStore() && !state.storeLimiter.tryAcquire(ip)) {
            state.stats.rejectedRateLimited.incrementAndGet()
            log.info("dht_store accepted=false reason=rate_limited")
            DhtRpcResponse.newBuilder().setStore(StoreResponse.newBuilder().setAccepted(false)).build()
        } else {
            state.dht.handle(request).also { logDhtStore(request, it) }
        }
        session.outgoing.send(Frame.Binary(true, response.toByteArray()))
    } catch (_: ClosedReceiveChannelException) {
        // client disconnected
    } catch (e: InvalidProtocolBufferException) {
        state.stats.rejectedInvalidHello.incrementAndGet()
        log.debug("dht_session_error reason=malformed_request detail=\"{}\"", e.message)
    } catch (e: IllegalStateException) {
        log.debug("dht_session_error reason=\"{}\"", e.message)
    }
}

private fun logDhtStore(request: DhtRpcRequest, response: DhtRpcResponse) {
    if (request.hasStore()) {
        log.info(
            "dht_store accepted={} key={}",
            response.store.accepted,
            IdentityHashMatcher.hashPrefixHex(request.store.record.identityHash.toByteArray()),
        )
    }
}

/** Periodic sweeps in a supervisor scope that lives with the application and dies on [ApplicationStopping]. */
private fun Application.startMaintenance(state: RelayNodeState) {
    val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
    monitor.subscribe(ApplicationStopping) { scope.cancel() }
    scope.launch {
        while (isActive) {
            delay(DHT_SWEEP_PERIOD_MS)
            state.dht.sweepExpired()
        }
    }
    scope.launch {
        while (isActive) {
            delay(LIMITER_SWEEP_PERIOD_MS)
            val removed = state.dialLimiter.sweep() + state.storeLimiter.sweep()
            log.debug("limiter_sweep removed={}", removed)
        }
    }
}
