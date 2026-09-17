package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chat thread: exactly one of [contactId] (1:1) and [groupId] (group) is set.
 *
 * Both are nullable rather than a sealed pair because SQLite has no sum type;
 * the invariant is enforced where conversations are created, and every read path
 * branches on which one is present.
 */
@Entity(
    tableName = "conversation",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // Unique on groupId: one conversation per group. SQLite treats NULLs as
    // distinct, so every 1:1 row (groupId NULL) still passes.
    indices = [Index("contactId"), Index(value = ["groupId"], unique = true)],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val contactId: String?,
    val groupId: String? = null,
    val lastMessageId: String?,
    val lastActivityUnixMs: Long,
    val unreadCount: Int,
    val muted: Boolean,
)

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["messageId"], unique = true),
        Index(value = ["conversationId", "direction", "status"], name = "index_message_conv_dir_status"),
        // Windowed paging: WHERE conversationId = ? ORDER BY createdAtUnixMs DESC LIMIT ?
        Index(value = ["conversationId", "createdAtUnixMs"], name = "index_message_conv_created"),
    ],
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val direction: MessageDirection,
    val contentType: MessageContentType,
    val body: String?,
    val replyToMessageId: String?,
    val status: DeliveryStatus,
    val createdAtUnixMs: Long,
    val sentAtUnixMs: Long?,
    val deliveredAtUnixMs: Long?,
    val readAtUnixMs: Long?,
    // Attachment metadata (IMAGE/VIDEO/FILE messages); path is app-private.
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentSizeBytes: Long? = null,
    val attachmentPath: String? = null,
    /** SHA-256 of the plaintext file (sent in the transfer header, verified on receipt). */
    val attachmentSha256: ByteArray? = null,
    /** True when the file at [attachmentPath] is in the encrypted `VMA1` container format. */
    val attachmentEncrypted: Boolean = false,
    /**
     * Lowercase hex identity hash of whoever sent an INCOMING group message, so
     * the bubble can name and colour them without a contact row. Null for 1:1
     * chats and for our own messages, where the direction already says who sent it.
     */
    val senderIdentityHash: String? = null,
    /** Text sent alongside an attachment (photo caption); the body stays null for attachments. */
    val caption: String? = null,
    /** Voice/video length in milliseconds, so the bubble shows a duration before the file arrives. */
    val attachmentDurationMs: Long? = null,
    /** 64 amplitude buckets (0..255), one byte each, rendered as the voice bubble's waveform. */
    val attachmentWaveform: ByteArray? = null,
    /**
     * When this voice message was first listened to. Null means the "unplayed" dot is shown —
     * persisted rather than kept in memory, so it survives a restart instead of telling the
     * user they never heard something they did.
     */
    val attachmentPlayedAtUnixMs: Long? = null,
    /** When the sender last revised this message's text; null for one never edited. */
    val editedAtUnixMs: Long? = null,
    /**
     * When the sender deleted it for everyone, by the sender's clock, as the delete carried it;
     * null for a message that was not. Set together with the `DELETED` content type.
     */
    val deletedAtUnixMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        return scalarFields() == other.scalarFields() &&
            (attachmentSha256 ?: EMPTY).contentEquals(other.attachmentSha256 ?: EMPTY) &&
            (attachmentWaveform ?: EMPTY).contentEquals(other.attachmentWaveform ?: EMPTY)
    }

    override fun hashCode(): Int {
        var result = 31 * scalarFields().hashCode() + (attachmentSha256?.contentHashCode() ?: 0)
        result = 31 * result + (attachmentWaveform?.contentHashCode() ?: 0)
        return result
    }

    /** Every field except the byte array, so equality/hash stay in step with the data-class semantics. */
    private fun scalarFields(): List<Any?> = listOf(
        messageId,
        conversationId,
        direction,
        contentType,
        body,
        replyToMessageId,
        status,
        createdAtUnixMs,
        sentAtUnixMs,
        deliveredAtUnixMs,
        readAtUnixMs,
        attachmentName,
        attachmentMimeType,
        attachmentSizeBytes,
        attachmentPath,
        attachmentEncrypted,
        senderIdentityHash,
        caption,
        attachmentDurationMs,
        attachmentPlayedAtUnixMs,
        editedAtUnixMs,
        deletedAtUnixMs,
    )

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * One pending delivery of one message to one recipient.
 *
 * A group message fans out to N rows sharing a [messageId]; a 1:1 message is the
 * N = 1 case of the same thing, so there is a single delivery pipeline. Backoff,
 * receipt waiting and the mailbox hand-off are therefore per recipient: one
 * unreachable member never stalls delivery to the rest.
 */
@Entity(
    tableName = "outbox",
    primaryKeys = ["messageId", "recipientIdentityHash"],
    indices = [Index("conversationId"), Index("nextAttemptUnixMs"), Index("messageId")],
)
data class OutboxEntity(
    val messageId: String,
    /** Lowercase hex identity hash of the member this row delivers to. */
    val recipientIdentityHash: String,
    val conversationId: String,
    /**
     * The exact envelope to send, for a message the dispatcher cannot rebuild from
     * its row — a group control carries a snapshot of the group *at its version*,
     * not of the group as it is now. Null for ordinary messages.
     */
    val envelopeBytes: ByteArray?,
    val attemptCount: Int,
    val nextAttemptUnixMs: Long,
    val lastError: String?,
    // After the message is transport-delivered (status SENT) we keep the row and
    // re-send until a delivery receipt arrives; this counts those receipt-wait
    // re-sends so we can stop after a bounded number.
    val receiptWaitCount: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OutboxEntity
        return messageId == other.messageId &&
            recipientIdentityHash == other.recipientIdentityHash &&
            conversationId == other.conversationId &&
            envelopeBytes.contentEqualsOrNull(other.envelopeBytes) &&
            attemptCount == other.attemptCount &&
            nextAttemptUnixMs == other.nextAttemptUnixMs &&
            lastError == other.lastError &&
            receiptWaitCount == other.receiptWaitCount
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + recipientIdentityHash.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + (envelopeBytes?.contentHashCode() ?: 0)
        result = 31 * result + attemptCount
        result = 31 * result + nextAttemptUnixMs.hashCode()
        result = 31 * result + (lastError?.hashCode() ?: 0)
        result = 31 * result + receiptWaitCount
        return result
    }

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }
}
