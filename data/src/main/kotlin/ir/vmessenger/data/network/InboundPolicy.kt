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

    /** An edit or a delete-for-everyone. Ownership of the target is checked by its handler. */
    MESSAGE_REVISION,

    /** A peer telling us their display name or avatar changed. */
    PROFILE_UPDATE,

    /** A request that we share our location. Verified contacts only; see [InboundPolicy]. */
    GPS_BUZZER,

    /** Call set-up or tear-down. Never audio: media travels on its own channel. */
    CALL_SIGNAL,
    ;

    /**
     * Whether the peer only sends this to someone they have as a contact (or whose request they are
     * answering). Node hints go to every peer a session is opened with, so they prove nothing.
     */
    val provesPeerHasUs: Boolean get() = this != NETWORK_NODES

    companion object {
        /** The policy-relevant kind of [envelope], or null for infrastructure traffic (mailbox, relay). */
        fun of(envelope: MessageEnvelope): InboundKind? = conversational(envelope) ?: management(envelope)

        /** Things that belong to a thread. */
        private fun conversational(envelope: MessageEnvelope): InboundKind? = when {
            envelope.hasChat() -> CHAT
            envelope.hasAttachmentInfo() || envelope.hasAttachmentChunk() -> ATTACHMENT
            envelope.hasReceipt() -> RECEIPT
            envelope.hasMessageEdit() || envelope.hasMessageDelete() -> MESSAGE_REVISION
            else -> null
        }

        /** Things that change what we know about a peer or the network, rather than a thread. */
        private fun management(envelope: MessageEnvelope): InboundKind? = when {
            envelope.hasLocation() -> LOCATION
            envelope.hasControl() -> CONTROL
            envelope.hasContactRequest() -> CONTACT_REQUEST
            envelope.hasContactResponse() -> CONTACT_RESPONSE
            envelope.hasNetworkNodes() -> NETWORK_NODES
            envelope.hasGroupControl() -> GROUP_CONTROL
            envelope.hasProfileUpdate() -> PROFILE_UPDATE
            envelope.hasGpsBuzzer() -> GPS_BUZZER
            envelope.hasCallSignal() -> CALL_SIGNAL
            else -> null
        }
    }
}

/**
 * Single place that decides whether an authenticated sender may deliver a given
 * kind of envelope. [contact] is null for strangers (peers we have no row for).
 *
 * Note that this fails OPEN, not closed: the caller only consults it when
 * [InboundKind.of] recognised the envelope, so a content arm added to the proto
 * and forgotten here would skip the check entirely rather than be refused. Any
 * new arm must land in both places in the same change.
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
        InboundKind.MESSAGE_REVISION,
        InboundKind.PROFILE_UPDATE,
        // Only an approved contact may make this phone ring. A stranger who could would have a
        // way to disturb someone who never agreed to hear from them.
        InboundKind.CALL_SIGNAL,
        -> contact != null &&
            !contact.blocked &&
            contact.relationshipStatus == ContactRelationshipStatus.APPROVED
        // Stricter than the rest, deliberately: asking someone to reveal where they are is only
        // allowed between contacts who have confirmed each other's safety numbers. Checked here
        // rather than trusting the sender's own UI to have checked.
        InboundKind.GPS_BUZZER,
        -> contact != null &&
            !contact.blocked &&
            contact.relationshipStatus == ContactRelationshipStatus.APPROVED &&
            contact.verified
    }
}
