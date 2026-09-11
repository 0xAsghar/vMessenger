package ir.vmessenger.node

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Runs [relayNodeModule] on loopback (nginx terminates TLS in front of it) and
 * shuts down gracefully: every listener gets a `GOING_AWAY` close first so the
 * apps reconnect right away instead of waiting for their socket timeout.
 */
class RelayNodeServer(private val state: RelayNodeState) {
    private val log = LoggerFactory.getLogger(RelayNodeServer::class.java)

    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        val cfg = state.config
        log.info("relay_node_starting bind=127.0.0.1:{} {}", cfg.port, cfg.describe())
        val server = embeddedServer(CIO, host = "127.0.0.1", port = cfg.port) { relayNodeModule(state) }
        engine = server
        // `start(wait = true)` would otherwise register Ktor's own JVM shutdown hook,
        // which stops the engine after a 1 s grace period concurrently with ours;
        // hook order is unspecified, so listeners could be cut off before the
        // GOING_AWAY frame is flushed. Ours must be the only hook.
        System.setProperty(KTOR_SHUTDOWN_HOOK_PROPERTY, "false")
        Runtime.getRuntime().addShutdownHook(Thread(::shutdown, "vmessenger-node-shutdown"))
        server.start(wait = true)
    }

    /** Closes every listener with `GOING_AWAY "restarting"`, then stops the engine. */
    fun shutdown() {
        log.info("relay_node_stopping listeners={} circuits={}", state.listeners.size, state.stats.activeCircuits.get())
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_GRACE_MS) {
                state.closeAllListeners(CloseReason(CloseReason.Codes.GOING_AWAY, "restarting"))
            }
        }
        engine?.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
        log.info("relay_node_stopped")
    }

    private companion object {
        const val SHUTDOWN_GRACE_MS = 2_000L
        const val SHUTDOWN_TIMEOUT_MS = 5_000L

        /** Ktor's switch for the hook `EmbeddedServer.start(wait = true)` adds (see `ShutdownHookJvm.kt`). */
        const val KTOR_SHUTDOWN_HOOK_PROPERTY = "io.ktor.server.engine.ShutdownHook"
    }
}
