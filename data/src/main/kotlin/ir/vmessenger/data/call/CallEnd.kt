package ir.vmessenger.data.call

/** Why a call ended, when the person who placed it is owed a word about it. */
enum class CallEndReason {
    /** The invite could not be delivered: they are offline, or not listening yet. */
    Unreachable,
    Declined,

    /** They are on another call. */
    Busy,

    /** It rang out unanswered. */
    NoAnswer,

    /** Answered, but the audio path never came up. */
    Failed,
}

/** One such ending; see [CallCoordinator.ended]. */
data class CallEnd(val callId: String, val peerName: String, val reason: CallEndReason)
