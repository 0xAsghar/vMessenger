package ir.vmessenger.data.call

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
 */
interface CallMediaPort {
    /**
     * Begins accepting the peer's media connection and returns the addresses to advertise, which
     * may be empty on a device with no reachable address — then the peer cannot connect and the
     * call ends rather than hanging in Connecting.
     */
    suspend fun accept(key: ByteArray, outgoing: Boolean, onEvent: suspend (CallEvent) -> Unit): List<String>

    /** Connects to the addresses the peer advertised, trying each in turn. */
    fun connect(addresses: List<String>, key: ByteArray, outgoing: Boolean, onEvent: suspend (CallEvent) -> Unit)

    /**
     * Mutes or unmutes the microphone. Muting sends silence rather than releasing the microphone,
     * so the privacy indicator stays lit for as long as the call holds it — the honest signal.
     */
    fun setMuted(muted: Boolean)

    /** Tears the audio path down: closes the socket, releases the microphone and speaker. */
    fun stop()
}
