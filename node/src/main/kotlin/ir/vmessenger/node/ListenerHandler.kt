package ir.vmessenger.node

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.network.RelayProof
import ir.vmessenger.core.proto.relay.v1.RelayHello
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.math.abs
import io.ktor.server.websocket.DefaultWebSocketServerSession as WsSession

/**
 * LISTENER hellos: proves the socket belongs to the identity it claims (v2 or,
 * during the transition, the 0.x transcript — selected by `proof_version`, see
 * [RelayProof]), rejects stale or replayed proofs, enforces the global and
 * per-address listener caps, then parks the socket in [RelayNodeState.listeners]
 * until it closes.
 */
class ListenerHandler(private val state: RelayNodeState) {
    private val log = LoggerFactory.getLogger(ListenerHandler::class.java)
    private val cfg = state.config
    private val stats = state.stats

    suspend fun handle(hello: RelayHello, session: WsSession, ip: String) {
        val listenerId = hello.listenerId.toByteArray()
        val entry = ListenerEntry(session, ip, listenerId)
        val rejection = validate(hello, listenerId) ?: register(entry)
        if (rejection != null) {
            RelayWire.reject(session, hello.circuitId, rejection, ip)
            return
        }
        try {
            serve(entry)
        } finally {
            unregister(entry)
        }
    }

    private fun validate(hello: RelayHello, listenerId: ByteArray): Rejection? {
        val identityPub = hello.identityPub.toByteArray()
        val now = state.clock()
        return when {
            listenerId.size != HASH_SIZE || identityPub.size != HASH_SIZE ->
                Rejection("Invalid listener identity", stats.rejectedInvalidHello)
            !sha256(identityPub).contentEquals(listenerId) ->
                Rejection("listener_id mismatch", stats.rejectedInvalidHello)
            abs(now - hello.ts) > cfg.proofMaxSkewMs ->
                Rejection("Stale listener proof", stats.rejectedStaleProof)
            !verifyProof(hello, identityPub) ->
                Rejection("Invalid listener proof", stats.rejectedInvalidHello)
            !state.replayCache.checkAndRemember(replayKey(listenerId, hello.ts), hello.ts, now) ->
                Rejection("Replayed listener proof", stats.rejectedReplayedProof)
            else -> null
        }
    }

    /** Applies the capacity caps and, when they pass, installs [entry] (replacing any previous socket). */
    private suspend fun register(entry: ListenerEntry): Rejection? {
        val previous = state.listeners[entry.key]
        // A client re-registering from the same address must not be blocked by its own old socket.
        val allowance = if (previous?.ip == entry.ip) cfg.maxListenersPerIp + 1 else cfg.maxListenersPerIp
        return when {
            previous == null && state.listeners.size >= cfg.maxListeners ->
                Rejection("Relay full", stats.rejectedRelayFull)
            !state.reserveListenerSlot(entry.ip, allowance) ->
                Rejection("Too many listeners from this address", stats.rejectedRateLimited)
            else -> {
                install(entry)
                null
            }
        }
    }

    private suspend fun install(entry: ListenerEntry) {
        val replaced = state.listeners.put(entry.key, entry)
        stats.listeners.set(state.listeners.size)
        replaced?.session?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced"))
        log.info(
            "listener_registered key={} replaced={} total={}",
            entry.prefix,
            replaced != null,
            state.listeners.size,
        )
    }

    /** Drains the listener socket; the listener never sends data frames, only a close. */
    private suspend fun serve(entry: ListenerEntry) {
        for (frame in entry.session.incoming) {
            if (frame is Frame.Close) break
        }
    }

    private fun unregister(entry: ListenerEntry) {
        state.listeners.remove(entry.key, entry)
        state.releaseListenerSlot(entry.ip)
        stats.listeners.set(state.listeners.size)
        log.info("listener_closed key={} total={}", entry.prefix, state.listeners.size)
    }

    private fun verifyProof(hello: RelayHello, identityPub: ByteArray): Boolean {
        val listenerId = hello.listenerId.toByteArray()
        val transcript = when (hello.proofVersion) {
            RelayProof.PROOF_VERSION_V2 -> RelayProof.buildListenerProofTranscript(listenerId, identityPub, hello.ts)
            PROOF_VERSION_LEGACY_UNSET, PROOF_VERSION_LEGACY ->
                RelayProof.buildLegacyListenerProofTranscript(listenerId, hello.ts)
            else -> return false
        }
        return Ed25519Verifier.verify(hello.proof.toByteArray(), transcript, identityPub)
    }

    private fun replayKey(listenerId: ByteArray, ts: Long): String =
        IdentityHashMatcher.hashPrefixHex(listenerId, listenerId.size) + ":" + ts

    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    private companion object {
        const val HASH_SIZE = 32

        /** 0.x apps never set `proof_version`; 1 is reserved for the same transcript if a client labels it. */
        const val PROOF_VERSION_LEGACY_UNSET = 0
        const val PROOF_VERSION_LEGACY = 1
    }
}
