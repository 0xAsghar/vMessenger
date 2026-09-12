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
}

enum class DeliveryStatus {
    QUEUED,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}
