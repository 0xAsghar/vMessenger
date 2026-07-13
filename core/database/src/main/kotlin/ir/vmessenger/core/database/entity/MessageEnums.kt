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
}

enum class DeliveryStatus {
    QUEUED,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}
