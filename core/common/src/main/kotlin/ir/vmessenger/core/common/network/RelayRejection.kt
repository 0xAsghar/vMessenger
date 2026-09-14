package ir.vmessenger.core.common.network

/**
 * The relay's own wording for the two endings a listener must act on instead of
 * simply dialling again.
 *
 * Matched as text because text is all there is: `RelayEvent` carries one message
 * field and no code, and a WebSocket close carries a free-form reason. Keep in
 * step with `node/.../ListenerHandler.kt`, which produces both.
 */
object RelayRejection {
    /** The listener proof's timestamp is outside the relay's skew window. */
    const val STALE_LISTENER_PROOF = "Stale listener proof"

    /** Close reason when a second socket took over the same identity's listener slot. */
    const val REPLACED = "replaced"

    fun isStaleProof(message: String?): Boolean =
        message?.contains(STALE_LISTENER_PROOF, ignoreCase = true) == true

    fun isReplaced(closeReason: String?): Boolean = closeReason.equals(REPLACED, ignoreCase = true)
}
