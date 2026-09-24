package ir.vmessenger.data.call

import ir.vmessenger.core.audio.CallAudio
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.network.messaging.RelayListener
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.InternetTransport
import ir.vmessenger.network.transport.RelayTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a call's audio travels: a direct socket when the two devices can reach each other, a relay
 * circuit when they cannot, and a new one of either when the one in use dies.
 *
 * Media does not ride the messaging session on purpose. That session's ratchet is capped at 65,536
 * frames, which a call at fifty frames a second exhausts in about eleven minutes, and one slow
 * message would stall audio behind it. So a call gets its own connections and its own key.
 *
 * The callee listens on its own port and on the relay it already listens on for messages, claiming
 * the circuits named for this call ([CallCircuits]); it advertises both. The caller dials every
 * advertised path at once, and [CallMediaSession] settles which one carries the audio. Whenever the
 * call has no path, the caller dials again — a fresh relay circuit each round — until the
 * coordinator gives up on it.
 */
@Singleton
class CallMediaService @Inject constructor(
    private val internetTransport: InternetTransport,
    private val relayTransport: RelayTransport,
    private val relayListener: RelayListener,
    private val audio: CallAudio,
    private val crypto: CryptoEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CallMediaPort {
    @Volatile
    private var call: MediaCall? = null

    override suspend fun accept(key: ByteArray, onEvent: suspend (CallEvent) -> Unit): List<MediaEndpoint> {
        stop()
        val prefix = CallCircuits.prefix(crypto, key)
        val media = start(key, MediaRole.Callee, onEvent, claimed = prefix)
        media.scope.launch { listenDirect(media.session) }
        relayListener.claimCircuits(prefix) { connection -> media.session.offer(connection) }
        val relay = relayListener.connectedRelayUrl?.let { MediaEndpoint(it, relay = true) }
        val endpoints = localAddresses(MEDIA_PORT).map { MediaEndpoint(it, relay = false) } + listOfNotNull(relay)
        if (endpoints.isEmpty()) {
            AppLogger.warn(TAG, "no address and no relay to advertise; the caller cannot reach this device")
        } else {
            AppLogger.info(TAG, "media accepting on ${endpoints.size} path(s), relay=${relay != null}")
        }
        return endpoints
    }

    override fun connect(
        endpoints: List<MediaEndpoint>,
        peerIdentityHash: ByteArray,
        key: ByteArray,
        onEvent: suspend (CallEvent) -> Unit,
    ) {
        stop()
        val target = DialTarget(
            direct = endpoints.filterNot { it.relay }.map { it.address },
            // Held to the same rule as any relay a contact advertises for messaging: wss:// with a host.
            relay = endpoints.firstOrNull { it.relay && relayTransport.canReach(Endpoint(RELAY, it.address)) }?.address,
            peer = peerIdentityHash.copyOf(),
            prefix = CallCircuits.prefix(crypto, key),
        )
        val media = start(key, MediaRole.Caller, onEvent, claimed = null)
        media.scope.launch { dialWhileDown(media, target) }
    }

    override fun setMuted(muted: Boolean) {
        audio.capture.muted = muted
    }

    override fun setSpeaker(on: Boolean) {
        audio.session.setSpeaker(on)
    }

    override fun stop() {
        val ending = call ?: return
        call = null
        ending.claimed?.let(relayListener::releaseCircuits)
        ending.session.close()
        audio.capture.muted = false
    }

    private fun start(
        key: ByteArray,
        role: MediaRole,
        onEvent: suspend (CallEvent) -> Unit,
        claimed: String?,
    ): MediaCall {
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler(TAG))
        val direction = if (role == MediaRole.Caller) MediaDirection.CallerToCallee else MediaDirection.CalleeToCaller
        val sealer = MediaSealer(crypto, key, direction)
        val session = CallMediaSession(sealer, CallAudioDevices(audio), role, onEvent, scope)
        return MediaCall(session, scope, claimed).also { call = it }
    }

    /** Direct connections for the whole call, not just its first: a caller that loses its path dials again. */
    private suspend fun listenDirect(session: CallMediaSession) {
        runCatching { internetTransport.listen(MEDIA_PORT).collect { session.offer(it) } }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                // Not fatal: the relay can still carry the call.
                AppLogger.warn(TAG, "direct media listener failed: ${failure.message}")
            }
    }

    /**
     * Rounds of dialling, each started only while nothing carries the call and given [ROUND_MS] to
     * bind before the next. A round's slower dials may land after it; they are still candidates.
     */
    private suspend fun dialWhileDown(media: MediaCall, target: DialTarget) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            media.session.up.first { !it }
            AppLogger.info(TAG, "opening media paths, round $attempt")
            target.direct.forEach { address -> media.scope.launch { dialDirect(address)?.let(media.session::offer) } }
            target.relay?.let { url ->
                val circuit = CallCircuits.id(target.prefix, attempt)
                media.scope.launch { dialRelay(url, target.peer, circuit)?.let(media.session::offer) }
            }
            attempt++
            withTimeoutOrNull(ROUND_MS) { media.session.up.first { it } }
        }
    }

    private suspend fun dialDirect(address: String): Connection? =
        internetTransport.connect(Endpoint(TransportIds.INTERNET, address))
            .onFailure { AppLogger.info(TAG, "direct media dial failed: ${it.message}") }
            .getOrNull()

    private suspend fun dialRelay(url: String, peer: ByteArray, circuitId: String): Connection? =
        relayTransport.connect(Endpoint(RELAY, url), peer, circuitId)
            .onFailure { AppLogger.info(TAG, "relay media dial failed: ${it.message}") }
            .getOrNull()

    /** One call's media: its session, the scope everything of it runs in, and the circuits it claimed. */
    private class MediaCall(val session: CallMediaSession, val scope: CoroutineScope, val claimed: String?)

    /** Everything the caller needs to open a path, fixed for the call. */
    private class DialTarget(val direct: List<String>, val relay: String?, val peer: ByteArray, val prefix: String)

    private companion object {
        const val TAG = "Call"

        /**
         * Its own port, so a media connection is never confused with a messaging one. Clear of
         * both neighbours: messaging listens on 48555 and the embedded DHT on 49555.
         */
        const val MEDIA_PORT = 48557

        /** How long a round of dials gets to produce a bound path before the next round starts. */
        const val ROUND_MS = 8_000L

        val RELAY = TransportIds.RELAY
    }
}

/**
 * This device's own IPv4 addresses, which is the best a phone can say about where to reach it: there
 * is no STUN here, so a NAT's outside address is simply not knowable. The relay covers what these do not.
 */
private fun localAddresses(port: Int): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .map { "$it:$port" }
        .toList()
}.getOrElse {
    AppLogger.warn("Call", "could not enumerate local addresses: ${it.message}")
    emptyList()
}
