package ir.vmessenger.domain.model

/**
 * One row of the chat list, produced by a single JOIN (conversation + contact +
 * last message). Everything the row renders is here, so the UI never combines a
 * conversation flow with a contact flow and never looks a message up per row.
 *
 * The `last*`/`preview*` fields are null for a conversation with no message yet.
 */
data class ConversationSummary(
    val id: String,
    val contactId: String,
    val contactName: String,
    /** Identicon seed; empty only if the contact row vanished under the conversation. */
    val identityHash: ByteArray,
    val preview: String?,
    val previewKind: MessagePreviewKind?,
    val lastDirection: MessageDirection?,
    val lastStatus: DeliveryStatus?,
    val lastActivityUnixMs: Long,
    val unreadCount: Int,
    val muted: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConversationSummary
        return scalarFields() == other.scalarFields() && identityHash.contentEquals(other.identityHash)
    }

    override fun hashCode(): Int = 31 * scalarFields().hashCode() + identityHash.contentHashCode()

    /** Every field except the byte array, so equality/hash keep data-class semantics. */
    private fun scalarFields(): List<Any?> = listOf(
        id,
        contactId,
        contactName,
        preview,
        previewKind,
        lastDirection,
        lastStatus,
        lastActivityUnixMs,
        unreadCount,
        muted,
    )
}
