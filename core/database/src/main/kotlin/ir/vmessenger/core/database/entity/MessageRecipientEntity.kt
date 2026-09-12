package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Per-recipient delivery state of an outgoing message.
 *
 * Groups have no server, so a group message is N pairwise sends and every
 * recipient has their own status. `message.status` stays the single value the
 * bubble renders and is recomputed from these rows (READ when all read,
 * DELIVERED when all delivered, SENT when any sent, FAILED when all failed).
 *
 * 1:1 messages take the same path with exactly one row, so there is one delivery
 * pipeline rather than two.
 */
@Entity(
    tableName = "message_recipient",
    primaryKeys = ["messageId", "identityHash"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("identityHash")],
)
data class MessageRecipientEntity(
    val messageId: String,
    /** Lowercase hex of the recipient's identity hash. */
    val identityHash: String,
    val status: DeliveryStatus,
    val sentAtUnixMs: Long?,
    val deliveredAtUnixMs: Long?,
    val readAtUnixMs: Long?,
)
