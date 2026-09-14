package ir.vmessenger.core.database.entity

enum class MessageDirection {
    OUTGOING,
    INCOMING,
}

enum class MessageContentType {
    TEXT,
    LOCATION_CONTROL,
    RECEIPT,
    IMAGE,
    VIDEO,
    FILE,

    /** Voice message: an attachment with a duration and a waveform. */
    AUDIO,

    /** A membership change rendered as a centred system line, not a bubble. */
    GROUP_CONTROL,

    /**
     * A message the sender asked everyone to delete, kept as a tombstone rather than removed.
     *
     * Removing the row outright would strand the conversation's unread count, which is only ever
     * incremented and never decremented anywhere, leaving a permanent phantom badge; it would also
     * break any reply quote pointing at it. A visible "this was deleted" line is the honest thing
     * to show in any case — the peer knows they deleted it, and hiding that it ever existed is a
     * promise this app cannot keep.
     */
    DELETED,

    /**
     * An edit or delete-for-everyone in flight, never drawn.
     *
     * It is a message row only because that is what the outbox and the per-recipient delivery
     * table are keyed on — reusing them buys retry, the mailbox hand-off, the 24-hour window and
     * receipt-driven completion for free. Every query that feeds the UI filters it out.
     */
    MESSAGE_CONTROL,
}

enum class DeliveryStatus {
    QUEUED,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}
