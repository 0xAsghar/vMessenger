package ir.vmessenger.data.network

import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.proto.app.v1.MessageEnvelope

/** What an authenticated peer is trying to send us; drives [InboundPolicy]. */
enum class InboundKind {
    CHAT,
    ATTACHMENT,
    LOCATION,
    CONTROL,
    RECEIPT,
    CONTACT_REQUEST,
    CONTACT_RESPONSE,

    /** Peer-exchange node hints: only an approved contact may grow our node tables. */
    NETWORK_NODES,

    /** A membership change. Who may actually apply it is decided by [GroupControlHandler]. */
    GROUP_CONTROL,
    ;

    companion object {
        /** The policy-relevant kind of [envelope], or null for infrastructure traffic (mailbox, relay). */
        fun of(envelope: MessageEnvelope): InboundKind? = when {
            envelope.hasChat() -> CHAT
            envelope.hasAttachmentInfo() || envelope.hasAttachmentChunk() -> ATTACHMENT
            envelope.hasLocation() -> LOCATION
            envelope.hasControl() -> CONTROL
            envelope.hasReceipt() -> RECEIPT
            envelope.hasContactRequest() -> CONTACT_REQUEST
            envelope.hasContactResponse() -> CONTACT_RESPONSE
            envelope.hasNetworkNodes() -> NETWORK_NODES
            envelope.hasGroupControl() -> GROUP_CONTROL
            else -> null
        }
    }
}

/**
 * Single place that decides whether an authenticated sender may deliver a given
 * kind of envelope. [contact] is null for strangers (peers we have no row for).
 *
 * - Chat, attachments, location, control, receipts, group controls and
 *   network-node hints need an APPROVED, non-blocked contact.
 * - Contact requests and responses only need the sender not to be blocked, so
 *   strangers can introduce themselves and pending contacts can answer.
 *
 * Blocked contacts are additionally refused at the handshake and skipped by
 * the outbox, so this is the last line, not the only one.
 */
object InboundPolicy {
    fun allows(contact: ContactEntity?, kind: InboundKind): Boolean = when (kind) {
        InboundKind.CONTACT_REQUEST,
        InboundKind.CONTACT_RESPONSE,
        -> contact?.blocked != true
        InboundKind.CHAT,
        InboundKind.ATTACHMENT,
        InboundKind.LOCATION,
        InboundKind.CONTROL,
        InboundKind.RECEIPT,
        InboundKind.NETWORK_NODES,
        InboundKind.GROUP_CONTROL,
        -> contact != null &&
            !contact.blocked &&
            contact.relationshipStatus == ContactRelationshipStatus.APPROVED
    }
}
