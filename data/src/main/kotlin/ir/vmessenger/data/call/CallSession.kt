package ir.vmessenger.data.call

/**
 * The call the UI draws, and nothing more.
 *
 * Deliberately carries no key material. The media key derived for a call lives in
 * [CallCoordinator] alone: this object is observed by composables, held in snapshots and logged in
 * diagnostics, and a key has no business in any of those places.
 */
data class CallSession(
    val callId: String,
    val contactId: String,
    val peerName: String,
    /** True when this device placed the call; decides which screen and which signals to send. */
    val outgoing: Boolean,
    val state: CallState,
    val muted: Boolean = false,
    /** True once the peer's device reported that it is alerting. */
    val peerAlerting: Boolean = false,
)
