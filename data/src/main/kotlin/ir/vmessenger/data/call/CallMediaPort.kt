package ir.vmessenger.data.call

/** Somewhere the callee can be reached for a call's audio. */
data class MediaEndpoint(
    /** `host:port` for a direct socket, or a relay's `wss://` URL. */
    val address: String,
    /** A relay the caller dials through, naming the callee's listener, rather than a socket to open. */
    val relay: Boolean,
)

/**
 * The call's audio path, kept behind an interface so [CallCoordinator] — the class that decides
 * whether a call happens at all — can be unit-tested without a socket or a microphone.
 *
 * Both methods are handed the per-call key rather than fetching it, so the audio path cannot start
 * itself: it has nothing to start with until the coordinator reaches an accept.
 *
 * An implementation **takes ownership of the `key` array** and zeroes it once the path ends. The
 * caller therefore passes a copy and zeroes its own, because [stop] only *requests* cancellation —
 * zeroing an array the media loop still holds would leave it sealing audio under an all-zero key.
 *
 * Once started, the path looks after itself for the rest of the call: it reports
 * [CallEvent.MediaUp] when audio first flows, [CallEvent.MediaLost] and [CallEvent.MediaRestored]
 * as the connection carrying it drops and is replaced, and [CallEvent.MediaFailed] when it cannot
 * go on at all. Ending the call is the coordinator's decision, and [stop] is how it says so.
 */
interface CallMediaPort {
    /**
     * Begins accepting the caller's media connections — for the whole call, since a caller that
     * loses its path dials again — and returns where to reach this device: its own addresses, and
     * the relay it is listening on. Empty when there is nowhere, and then the call ends rather than
     * hanging in Connecting.
     */
    suspend fun accept(key: ByteArray, onEvent: suspend (CallEvent) -> Unit): List<MediaEndpoint>

    /**
     * Opens paths to what the callee advertised — every direct address and its relay at once — and
     * keeps opening new ones whenever the call has none, until [stop].
     */
    fun connect(
        endpoints: List<MediaEndpoint>,
        peerIdentityHash: ByteArray,
        key: ByteArray,
        onEvent: suspend (CallEvent) -> Unit,
    )

    /**
     * Mutes or unmutes the microphone. Muting sends silence rather than releasing the microphone,
     * so the privacy indicator stays lit for as long as the call holds it — the honest signal.
     */
    fun setMuted(muted: Boolean)

    /** Earpiece or speaker. A request to the platform, which a headset or a car may overrule. */
    fun setSpeaker(on: Boolean)

    /** Tears the audio path down: closes every connection, releases the microphone and speaker. */
    fun stop()
}
