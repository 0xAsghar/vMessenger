package ir.vmessenger.node

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.relay.v1.RelayEvent
import ir.vmessenger.core.proto.relay.v1.RelayEventType
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.core.proto.relay.v1.RelayRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ir.vmessenger.core.common.network.RelayProof
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.ktor.server.websocket.DefaultWebSocketServerSession as WsSession

class RelayNodeServer(
    private val port: Int,
    private val publicHost: String,
    private val dhtHandler: DhtRequestHandler = DhtRequestHandler(port, publicHost),
) {
    private val sodium = LazySodiumJava(SodiumJava())
    private val listeners = ConcurrentHashMap<String, WsSession>()
    private val pendingDialers = ConcurrentHashMap<String, PendingDialer>()
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class PendingDialer(
        val session: WsSession,
        val finished: CompletableDeferred<Unit>,
    )

    @Suppress("TooGenericExceptionCaught")
    fun start() {
        println("vMessenger relay node listening on 127.0.0.1:$port (public host: $publicHost)")
        embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(WebSockets)
            routing {
                get("/healthz") {
                    call.respondText("ok")
                }
                webSocket("/dht") {
                    try {
                        val frame = incoming.receive() as? Frame.Binary ?: return@webSocket
                        val request = DhtRpcRequest.parseFrom(frame.readBytes())
                        val response = dhtHandler.handle(request)
                        outgoing.send(Frame.Binary(true, response.toByteArray()))
                    } catch (e: Exception) {
                        System.err.println("DHT ws error: ${e.message}")
                    }
                }
                webSocket("/relay") {
                    handleRelaySession(this)
                }
            }
        }.start(wait = true)
    }

    private suspend fun handleRelaySession(session: WsSession) {
        try {
            val frame = session.incoming.receive() as? Frame.Binary ?: return
            val hello = RelayHello.parseFrom(frame.readBytes())
            when (hello.role) {
                RelayRole.RELAY_ROLE_LISTENER -> handleListener(hello, session)
                RelayRole.RELAY_ROLE_DIALER -> handleDialer(hello, session)
                RelayRole.RELAY_ROLE_ACCEPT -> handleAccept(hello, session)
                else -> sendRelayError(session, hello.circuitId, "Unknown relay role")
            }
        } catch (_: ClosedReceiveChannelException) {
            // client disconnected
        } catch (e: Exception) {
            System.err.println("Relay ws error: ${e.message}")
        }
    }

    private suspend fun handleListener(hello: RelayHello, session: WsSession) {
        val listenerId = hello.listenerId.toByteArray()
        val identityPub = hello.identityPub.toByteArray()
        if (listenerId.size != 32 || identityPub.size != 32) {
            sendRelayError(session, hello.circuitId, "Invalid listener identity")
            return
        }
        val computed = MessageDigest.getInstance("SHA-256").digest(identityPub)
        if (!computed.contentEquals(listenerId)) {
            sendRelayError(session, hello.circuitId, "listener_id mismatch")
            return
        }
        if (!verifyListenerProof(hello, identityPub)) {
            sendRelayError(session, hello.circuitId, "Invalid listener proof")
            return
        }
        val key = listenerId.contentHashCode().toString()
        listeners[key]?.let { old ->
            runCatching { old.close(CloseReason(CloseReason.Codes.NORMAL, "replaced")) }
        }
        listeners[key] = session
        try {
            for (incomingFrame in session.incoming) {
                if (incomingFrame is Frame.Close) break
            }
        } finally {
            listeners.remove(key, session)
        }
    }

    private suspend fun handleDialer(hello: RelayHello, session: WsSession) {
        val targetId = hello.targetId.toByteArray()
        if (targetId.size != 32) {
            sendRelayError(session, hello.circuitId, "Invalid target_id")
            return
        }
        val listenerKey = targetId.contentHashCode().toString()
        val listener = listeners[listenerKey]
        if (listener == null) {
            sendRelayError(session, hello.circuitId, "Peer not listening on relay")
            return
        }
        val circuitId = hello.circuitId.ifBlank { UUID.randomUUID().toString() }
        val finished = CompletableDeferred<Unit>()
        pendingDialers[circuitId] = PendingDialer(session, finished)
        val incoming = RelayEvent.newBuilder()
            .setType(RelayEventType.RELAY_EVENT_TYPE_INCOMING)
            .setCircuitId(circuitId)
            .build()
        listener.outgoing.send(Frame.Binary(true, incoming.toByteArray()))
        finished.await()
    }

    private suspend fun handleAccept(hello: RelayHello, session: WsSession) {
        val circuitId = hello.circuitId
        if (circuitId.isBlank()) {
            sendRelayError(session, circuitId, "Missing circuit_id")
            return
        }
        val pending = pendingDialers.remove(circuitId)
        if (pending == null) {
            sendRelayError(session, circuitId, "Unknown or expired circuit")
            return
        }
        val dialer = pending.session
        val ready = RelayEvent.newBuilder()
            .setType(RelayEventType.RELAY_EVENT_TYPE_READY)
            .setCircuitId(circuitId)
            .build()
        val readyBytes = ready.toByteArray()
        dialer.outgoing.send(Frame.Binary(true, readyBytes))
        session.outgoing.send(Frame.Binary(true, readyBytes))
        bridgeScope.launch {
            try {
                coroutineScope {
                    launch { bridgeSessions(dialer, session) }
                    launch { bridgeSessions(session, dialer) }
                }
            } finally {
                pending.finished.complete(Unit)
            }
        }
        pending.finished.await()
    }

    private suspend fun bridgeSessions(from: WsSession, to: WsSession) {
        try {
            for (frame in from.incoming) {
                when (frame) {
                    is Frame.Binary -> to.outgoing.send(Frame.Binary(true, frame.readBytes()))
                    is Frame.Close -> {
                        to.close(CloseReason(CloseReason.Codes.NORMAL, "peer closed"))
                        break
                    }
                    else -> Unit
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            runCatching { to.close(CloseReason(CloseReason.Codes.NORMAL, "peer closed")) }
        } catch (e: Exception) {
            runCatching { to.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, e.message ?: "error")) }
        }
    }

    private suspend fun sendRelayError(session: WsSession, circuitId: String, message: String) {
        val event = RelayEvent.newBuilder()
            .setType(RelayEventType.RELAY_EVENT_TYPE_ERROR)
            .setCircuitId(circuitId)
            .setMessage(message)
            .build()
        session.outgoing.send(Frame.Binary(true, event.toByteArray()))
        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, message))
    }

    private fun verifyListenerProof(hello: RelayHello, identityPub: ByteArray): Boolean {
        val transcript = RelayProof.buildListenerProofTranscript(hello.listenerId.toByteArray(), hello.ts)
        return sodium.cryptoSignVerifyDetached(
            hello.proof.toByteArray(),
            transcript,
            transcript.size,
            identityPub,
        )
    }

    companion object {
        fun buildListenerProofTranscript(listenerId: ByteArray, ts: Long): ByteArray =
            RelayProof.buildListenerProofTranscript(listenerId, ts)
    }
}
