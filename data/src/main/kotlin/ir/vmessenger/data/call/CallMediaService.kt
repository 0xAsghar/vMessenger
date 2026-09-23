package ir.vmessenger.data.call

import ir.vmessenger.core.audio.CallAudio
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.InternetTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The audio path: one TCP connection per call, carrying nothing but sealed Opus frames.
 *
 * Media does not ride the messaging session on purpose. That session's ratchet is capped at 65,536
 * frames, which a call at fifty frames a second exhausts in about eleven minutes, and one slow
 * message would stall audio behind it. So a call gets its own socket, its own key, and its own port.
 *
 * **The reachability limit, stated plainly:** the accepting side listens and advertises its local
 * addresses; the dialling side connects to one of them. With no UDP hole-punching in the app yet,
 * that works when the two devices can reach each other directly — the same LAN, a VPN, or a
 * reachable host — and fails cleanly otherwise, ending the call instead of leaving it silent.
 * Carrying media over a relay is the documented follow-up; it needs the relay's inbound path to
 * distinguish a media circuit from a messaging one, which is surgery on the handshake boundary and
 * not something to attempt without two devices in hand.
 */
@Singleton
class CallMediaService @Inject constructor(
    private val internetTransport: InternetTransport,
    private val audio: CallAudio,
    private val crypto: CryptoEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CallMediaPort {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler(TAG))
    private var job: Job? = null

    override suspend fun accept(
        key: ByteArray,
        outgoing: Boolean,
        onEvent: suspend (CallEvent) -> Unit,
    ): List<String> {
        stop()
        val addresses = localAddresses()
        if (addresses.isEmpty()) {
            AppLogger.warn(TAG, "no reachable local address; the peer cannot open the media path")
            key.fill(0)
            return addresses
        }
        AppLogger.info(TAG, "media listening on $MEDIA_PORT, advertising ${addresses.size} address(es)")
        job = scope.launch {
            // One connection per call: the first to arrive is the peer's, and the listener closes
            // behind it rather than staying open for whatever else finds the port.
            internetTransport.listen(MEDIA_PORT).take(1).collect { connection ->
                carry(connection, key, outgoing, onEvent)
            }
        }
        return addresses
    }

    override fun connect(
        addresses: List<String>,
        key: ByteArray,
        outgoing: Boolean,
        onEvent: suspend (CallEvent) -> Unit,
    ) {
        stop()
        job = scope.launch {
            val connection = firstReachable(addresses)
            if (connection == null) {
                AppLogger.warn(TAG, "no advertised media address answered; ending the call")
                key.fill(0)
                onEvent(CallEvent.MediaLost)
            } else {
                AppLogger.info(TAG, "media connected to ${connection.remote.address}")
                carry(connection, key, outgoing, onEvent)
            }
        }
    }

    override fun setMuted(muted: Boolean) {
        audio.capture.muted = muted
    }

    override fun setSpeaker(on: Boolean) {
        audio.session.setSpeaker(on)
    }

    override fun stop() {
        job?.cancel()
        job = null
        audio.capture.muted = false
        audio.playback.close()
        audio.session.close()
    }

    /**
     * Runs one call's audio to the end of the connection.
     *
     * [CallEvent.MediaLost] is reported after the loops finish but outside the `finally`, so a
     * teardown this class was *told* to do ([stop]) does not report a loss back to the caller that
     * asked for it — only a connection that actually ended on its own does.
     */
    private suspend fun carry(
        connection: Connection,
        key: ByteArray,
        outgoing: Boolean,
        onEvent: suspend (CallEvent) -> Unit,
    ) {
        val codec = audio.codecs.create()
        val direction = if (outgoing) MediaDirection.CallerToCallee else MediaDirection.CalleeToCaller
        val channel = CallMediaChannel(crypto, codec, audio.playback, key, direction)
        // Before a single frame moves: the echo canceller, the earpiece routing and the volume
        // keys are all conditioned on communication mode, and focus is what stops the music.
        audio.session.open()
        try {
            // A failure is still an end: thrown past this point, it skipped the report below and
            // left the call on screen over a dead socket.
            val failure = runCatching {
                channel.run(connection, audio.capture.frames()) { onEvent(CallEvent.MediaUp) }
            }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            failure?.let { AppLogger.warn(TAG, "media path failed: ${it.message}") }
        } finally {
            withContext(NonCancellable) {
                runCatching { codec.close() }
                runCatching { connection.close() }
                // Restored, not merely left: a process that forgets it was in communication mode
                // leaves the whole device routing audio as though a call were still up.
                audio.session.close()
                // This array is ours (see [CallMediaPort]); nothing else holds it.
                key.fill(0)
            }
        }
        AppLogger.info(TAG, "media path ended")
        onEvent(CallEvent.MediaLost)
    }

    private suspend fun firstReachable(addresses: List<String>): Connection? =
        addresses.firstNotNullOfOrNull { address ->
            internetTransport.connect(Endpoint(TransportIds.INTERNET, address)).getOrNull()
        }

    /**
     * This device's own IPv4 addresses, which is the best a phone can say about where to reach it:
     * there is no STUN here, so a NAT's outside address is simply not knowable.
     */
    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .map { "$it:$MEDIA_PORT" }
            .toList()
    }.getOrElse {
        AppLogger.warn(TAG, "could not enumerate local addresses: ${it.message}")
        emptyList()
    }

    private companion object {
        const val TAG = "Call"

        /**
         * Its own port, so a media connection is never confused with a messaging one. Clear of
         * both neighbours: messaging listens on 48555 and the embedded DHT on 49555.
         */
        const val MEDIA_PORT = 48557
    }
}
