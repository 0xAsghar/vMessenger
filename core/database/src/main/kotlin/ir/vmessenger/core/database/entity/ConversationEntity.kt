package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("contactId")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val contactId: String,
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
    indices = [Index("conversationId"), Index(value = ["messageId"], unique = true)],
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
)

@Entity(
    tableName = "outbox",
    indices = [Index("conversationId"), Index("nextAttemptUnixMs")],
)
data class OutboxEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val sealedPayload: ByteArray?,
    val attemptCount: Int,
    val nextAttemptUnixMs: Long,
    val lastError: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OutboxEntity
        return messageId == other.messageId &&
            conversationId == other.conversationId &&
            sealedPayload.contentEqualsOrNull(other.sealedPayload) &&
            attemptCount == other.attemptCount &&
            nextAttemptUnixMs == other.nextAttemptUnixMs &&
            lastError == other.lastError
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + (sealedPayload?.contentHashCode() ?: 0)
        result = 31 * result + attemptCount
        result = 31 * result + nextAttemptUnixMs.hashCode()
        result = 31 * result + (lastError?.hashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }
}

@Entity(
    tableName = "session",
    indices = [Index(value = ["contactId"], unique = true)],
)
data class SessionEntity(
    @PrimaryKey val contactId: String,
    val sealedState: ByteArray,
    val updatedAtUnixMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SessionEntity
        return contactId == other.contactId &&
            sealedState.contentEquals(other.sealedState) &&
            updatedAtUnixMs == other.updatedAtUnixMs
    }

    override fun hashCode(): Int =
        31 * (31 * contactId.hashCode() + sealedState.contentHashCode()) + updatedAtUnixMs.hashCode()
}
