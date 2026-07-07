package ir.vmessenger.core.database.entity

enum class ContactRelationshipStatus {
    APPROVED,
    PENDING_OUT,
    PENDING_IN,
    REJECTED,
}

enum class ContactRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
}
