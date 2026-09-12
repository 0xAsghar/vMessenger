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
    /** Null for a group row; exactly one of this and [groupId] is set. */
    val contactId: String?,
    val groupId: String?,
    /** The contact's name, or the group's. */
    val contactName: String,
    /** Identicon seed of a 1:1 row; empty for a group, and if the contact row vanished under the conversation. */
    val identityHash: ByteArray,
    /** Identicon seed of a group row; null for 1:1. */
    val groupAvatarSeed: String?,
    /**
     * Who sent the last message of a group, so the row reads "Name: text" the way
     * every messenger shows it. Null for 1:1 rows and for our own last message.
     */
    val lastSenderName: String?,
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

    val isGroup: Boolean get() = groupId != null

    /** Every field except the byte array, so equality/hash keep data-class semantics. */
    private fun scalarFields(): List<Any?> = listOf(
        id,
        contactId,
        groupId,
        contactName,
        groupAvatarSeed,
        lastSenderName,
        preview,
        previewKind,
        lastDirection,
        lastStatus,
        lastActivityUnixMs,
        unreadCount,
        muted,
    )
}
