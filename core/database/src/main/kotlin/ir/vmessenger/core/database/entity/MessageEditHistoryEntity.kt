package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Whether a captured revision was an edit or a delete-for-everyone. */
enum class MessageRevisionKind {
    EDIT,
    DELETE,
}

/**
 * What a group message said before its sender changed or withdrew it.
 *
 * This table reverses a privacy property the app otherwise has — a delete really does erase the
 * text locally — so it is written **only** for a group whose creator switched audit retention on,
 * never for a 1:1 conversation, and every member of such a group is told so by a banner they cannot
 * dismiss. A member who does not accept that can leave.
 *
 * It holds only messages that were **actually sent** and later revised. A draft never leaves the
 * composer and is not recorded anywhere; nothing here captures what someone typed and chose not to
 * send.
 *
 * The CASCADE to `message` is load-bearing in the other direction too: when a self-destructing
 * message's row is purged on expiry, its captured revisions go with it. An audit table that
 * outlived an expiry would quietly defeat the timer that was the point.
 *
 * Not backed up. A backup is portable, and this content is retained under one group's disclosed
 * policy — it should not travel out of the device that recorded it.
 */
@Entity(
    tableName = "message_edit_history",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId"), Index(value = ["groupId", "capturedAtUnixMs"], name = "index_history_group_time")],
)
data class MessageEditHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    /** The group whose policy caused this capture; an audit is always scoped to one group. */
    val groupId: String,
    /** Lowercase hex identity hash of whoever wrote the message; null for our own. */
    val authorIdentityHash: String?,
    val revision: MessageRevisionKind,
    /** The text as it stood before the revision. */
    val body: String?,
    val caption: String?,
    val attachmentName: String?,
    /**
     * The attachment the message pointed at. The file is *not* deleted while retention is on, so
     * this path still resolves; with retention off the file goes and nothing is captured at all.
     */
    val attachmentPath: String?,
    /** By this device's clock — the moment it saw the revision, not when the sender made it. */
    val capturedAtUnixMs: Long,
)
