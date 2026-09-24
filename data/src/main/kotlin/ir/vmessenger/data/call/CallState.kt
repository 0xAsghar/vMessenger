package ir.vmessenger.data.call

/**
 * Where a call is.
 *
 * The states exist mainly to make one property true by construction: [microphoneOpen] holds in
 * exactly one of them. A call cannot capture audio while ringing, while connecting, or while
 * reconnecting — only once it is [Active], which is only reachable through an explicit accept. That
 * is the whole reason this is a machine rather than a pair of booleans, and it is what makes "this
 * app cannot listen to you without you answering" a property of the code instead of a claim.
 */
enum class CallState {
    Idle,

    /** We invited someone and are waiting; their device may not be alerting yet. */
    OutgoingRinging,

    /** Someone invited us and this phone is alerting. No audio is captured here. */
    IncomingRinging,

    /** Both sides agreed; the media path is being established. Still no audio. */
    Connecting,

    /** Media is up. The only state in which the microphone may be open. */
    Active,

    /** The media path dropped mid-call and is being re-established; the mic closes meanwhile. */
    Reconnecting,

    /** Tearing down. Terminal for this call; the next call starts from [Idle]. */
    Ending,
    ;

    /**
     * Whether the microphone may be open.
     *
     * True in [Active] alone. Every other state — including [Reconnecting], where a call is still
     * notionally in progress — closes it, so a stalled call cannot sit holding an open microphone.
     */
    val microphoneOpen: Boolean get() = this == Active

    /** Whether this state should be showing the user a call screen. */
    val onScreen: Boolean get() = this != Idle && this != Ending

    /**
     * Whether a microphone foreground service should be running.
     *
     * Every live state except [IncomingRinging], and that exception is the platform's rule rather
     * than a preference: [IncomingRinging] is the one state a call reaches from a background signal
     * with no user action behind it, and from Android 14 a microphone service cannot be started
     * there. A ringing phone therefore holds a notification and nothing more; the service starts on
     * the answer. Which is the behaviour to want anyway — the service is what can hear you.
     */
    val holdsMicrophoneService: Boolean get() = this != Idle && this != IncomingRinging && this != Ending
}

/** What can happen to a call. Local acts and peer signals are named apart, because they differ. */
sealed interface CallEvent {
    /** We placed the call. */
    data object DialOut : CallEvent

    /** An invite arrived from an approved contact. */
    data object InviteReceived : CallEvent

    /** The callee's device is alerting. */
    data object RingReceived : CallEvent

    /** The user on this device answered. */
    data object AcceptedHere : CallEvent

    /** The peer answered our invite. */
    data object AcceptReceived : CallEvent

    /** The media path carried its first frame. */
    data object MediaUp : CallEvent

    /** The media path went away mid-call. */
    data object MediaLost : CallEvent

    /** A dropped media path came back. */
    data object MediaRestored : CallEvent

    /**
     * The media path cannot go on — the microphone would not open or stopped — which no new
     * connection would fix. The coordinator ends the call on it rather than moving the machine.
     */
    data object MediaFailed : CallEvent

    /** The user on this device declined or hung up. */
    data object EndedHere : CallEvent

    /** The peer declined, hung up, was busy, or cancelled. */
    data object EndedByPeer : CallEvent

    /** Nobody answered, or reconnecting ran out of time. */
    data object TimedOut : CallEvent
}

/**
 * The next state, or null when the event does not apply.
 *
 * Returning null rather than throwing is deliberate: signalling races are normal — a hangup and an
 * accept can cross on the wire — and the loser of the race must be ignored, not crash the call.
 */
@Suppress("CyclomaticComplexMethod") // A transition table; splitting it would hide the shape.
fun CallState.next(event: CallEvent): CallState? = when (this) {
    CallState.Idle -> when (event) {
        CallEvent.DialOut -> CallState.OutgoingRinging
        CallEvent.InviteReceived -> CallState.IncomingRinging
        else -> null
    }

    CallState.OutgoingRinging -> when (event) {
        // Their phone started ringing: worth showing, but the state does not change.
        CallEvent.RingReceived -> CallState.OutgoingRinging
        CallEvent.AcceptReceived -> CallState.Connecting
        CallEvent.EndedByPeer, CallEvent.EndedHere, CallEvent.TimedOut -> CallState.Ending
        else -> null
    }

    CallState.IncomingRinging -> when (event) {
        CallEvent.AcceptedHere -> CallState.Connecting
        CallEvent.EndedHere, CallEvent.EndedByPeer, CallEvent.TimedOut -> CallState.Ending
        else -> null
    }

    CallState.Connecting -> when (event) {
        CallEvent.MediaUp -> CallState.Active
        CallEvent.EndedHere, CallEvent.EndedByPeer, CallEvent.TimedOut -> CallState.Ending
        else -> null
    }

    CallState.Active -> when (event) {
        CallEvent.MediaLost -> CallState.Reconnecting
        CallEvent.EndedHere, CallEvent.EndedByPeer -> CallState.Ending
        else -> null
    }

    CallState.Reconnecting -> when (event) {
        CallEvent.MediaRestored, CallEvent.MediaUp -> CallState.Active
        CallEvent.EndedHere, CallEvent.EndedByPeer, CallEvent.TimedOut -> CallState.Ending
        else -> null
    }

    // Terminal. A late signal for a call already over changes nothing.
    CallState.Ending -> null
}
