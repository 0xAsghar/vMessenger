package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import kotlinx.coroutines.flow.Flow

data class ConversationWithPreview(
    @Embedded val conversation: ConversationEntity,
    val lastMessagePreview: String?,
)

/**
 * One chat-list row, built by a single JOIN over conversation + contact + the
 * conversation's last message. The UI renders this verbatim, so it never has to
 * combine a conversation flow with a contact flow (and never re-queries per row).
 *
 * Every `last*` field is null for a conversation that has no message yet.
 */
data class ChatListRow(
    val conversationId: String,
    /** Null for a group row; exactly one of this and [groupId] is set. */
    val contactId: String?,
    val groupId: String?,
    val displayName: String?,
    val identityHash: ByteArray?,
    /** Identicon seed of a group row; null for 1:1. */
    val groupAvatarSeed: String?,
    /** Who sent the last group message, for the "name: text" preview; null for 1:1 and for our own. */
    val lastSenderName: String?,
    val lastMessageId: String?,
    val lastBody: String?,
    val lastAttachmentName: String?,
    val lastContentType: MessageContentType?,
    val lastDirection: MessageDirection?,
    val lastStatus: DeliveryStatus?,
    val lastCreatedAtUnixMs: Long?,
    val lastActivityUnixMs: Long,
    val unreadCount: Int,
    val muted: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChatListRow
        return scalarFields() == other.scalarFields() &&
            (identityHash ?: EMPTY).contentEquals(other.identityHash ?: EMPTY)
    }

    override fun hashCode(): Int = 31 * scalarFields().hashCode() + (identityHash?.contentHashCode() ?: 0)

    /** Every field except the byte array, so equality/hash keep data-class semantics. */
    private fun scalarFields(): List<Any?> = listOf(
        conversationId,
        contactId,
        groupId,
        displayName,
        groupAvatarSeed,
        lastSenderName,
        lastMessageId,
        lastBody,
        lastAttachmentName,
        lastContentType,
        lastDirection,
        lastStatus,
        lastCreatedAtUnixMs,
        lastActivityUnixMs,
        unreadCount,
        muted,
    )

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * A message plus the preview of the message it replies to, resolved by a
 * LEFT JOIN so rendering a window of replies never costs one lookup per row.
 * The `reply*` fields are null when the message is not a reply (or the quoted
 * message was deleted locally, or a peer quoted an id from another chat).
 */
data class MessageWithReply(
    @Embedded val message: MessageEntity,
    val replyBody: String?,
    val replyAttachmentName: String?,
    val replyContentType: MessageContentType?,
    val replyDirection: MessageDirection?,
    /** Last delivery failure recorded by the outbox dispatcher; null once delivered or never failed. */
    val lastError: String?,
    /**
     * Display name of an incoming group message's sender — the user's own name for
     * them when they are a contact, otherwise the name from the group snapshot.
     * Null in 1:1 chats and for our own messages.
     */
    val senderName: String?,
)

@Dao
// One query per thing the chat list and conversation screen ask of a conversation.
@Suppress("TooManyFunctions")
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity)

    /**
     * Updates an existing conversation row in place.
     *
     * Do NOT use [upsert] to modify an existing conversation: it runs
     * INSERT OR REPLACE, which deletes the old row first and triggers the
     * message table's ON DELETE CASCADE, wiping every message in the
     * conversation. A real UPDATE keeps the children intact.
     */
    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("SELECT * FROM conversation ORDER BY lastActivityUnixMs DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT c.*, m.body AS lastMessagePreview
        FROM conversation c
        LEFT JOIN message m ON m.messageId = c.lastMessageId
        ORDER BY c.lastActivityUnixMs DESC
        """,
    )
    fun observeAllWithPreview(): Flow<List<ConversationWithPreview>>

    @Query("SELECT * FROM conversation WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversation WHERE contactId = :contactId LIMIT 1")
    suspend fun getByContactId(contactId: String): ConversationEntity?

    @Query("SELECT * FROM conversation WHERE groupId = :groupId LIMIT 1")
    suspend fun getByGroupId(groupId: String): ConversationEntity?

    @Query("UPDATE conversation SET unreadCount = 0 WHERE id = :id")
    suspend fun resetUnread(id: String)

    /**
     * The whole chat list in one query: conversation + its contact + its last
     * message. `COLLATE NOCASE` is not applied — ordering is by recency, and
     * name sorting is not part of this screen.
     */
    @Query(
        """
        SELECT
            c.id AS conversationId,
            c.contactId AS contactId,
            c.groupId AS groupId,
            COALESCE(g.name, ct.displayName) AS displayName,
            ct.identityHash AS identityHash,
            g.avatarSeed AS groupAvatarSeed,
            gm.displayName AS lastSenderName,
            m.messageId AS lastMessageId,
            m.body AS lastBody,
            m.attachmentName AS lastAttachmentName,
            m.contentType AS lastContentType,
            m.direction AS lastDirection,
            m.status AS lastStatus,
            m.createdAtUnixMs AS lastCreatedAtUnixMs,
            c.lastActivityUnixMs AS lastActivityUnixMs,
            c.unreadCount AS unreadCount,
            c.muted AS muted
        FROM conversation c
        LEFT JOIN contact ct ON ct.id = c.contactId
        LEFT JOIN chat_group g ON g.id = c.groupId
        LEFT JOIN message m ON m.messageId = c.lastMessageId
        LEFT JOIN chat_group_member gm
            ON gm.groupId = c.groupId AND gm.identityHash = m.senderIdentityHash
        ORDER BY c.lastActivityUnixMs DESC
        """,
    )
    fun observeChatList(): Flow<List<ChatListRow>>

    @Query("UPDATE conversation SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: String, muted: Boolean)

    /**
     * Messages cascade with the conversation (foreign key). Outbox rows do **not** —
     * `outbox` has no foreign key — so drop them first with
     * [OutboxDao.removeByConversation], or the dispatcher keeps retrying orphans.
     */
    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE conversation SET lastMessageId = :messageId WHERE id = :id")
    suspend fun setLastMessageId(id: String, messageId: String?)
}

/** One unread incoming message and its group sender (null in a 1:1 chat). */
data class UnreadIncoming(val messageId: String, val senderIdentityHash: String?)

@Dao
// One query per message operation (paging, status transitions, deletes); a DAO is a query catalogue.
@Suppress("TooManyFunctions")
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    /**
     * A real UPDATE, deliberately not an upsert.
     *
     * `message` is the parent of `message_recipient` with ON DELETE CASCADE, and an
     * `@Insert(REPLACE)` is a DELETE followed by an INSERT — it would silently take every
     * per-recipient delivery row with it, losing who had already received the message.
     */
    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM message WHERE conversationId = :cid ORDER BY createdAtUnixMs ASC")
    fun observeConversation(cid: String): Flow<List<MessageEntity>>

    @Query("UPDATE message SET status = :status, deliveredAtUnixMs = :ts WHERE messageId = :id")
    suspend fun markDelivered(id: String, status: DeliveryStatus, ts: Long)

    @Query("UPDATE message SET status = :status, readAtUnixMs = :ts WHERE messageId = :id")
    suspend fun markRead(id: String, status: DeliveryStatus, ts: Long)

    @Query("UPDATE message SET status = :status, sentAtUnixMs = :ts WHERE messageId = :id")
    suspend fun markSent(id: String, status: DeliveryStatus, ts: Long)

    @Query("UPDATE message SET status = :status WHERE messageId = :id")
    suspend fun updateStatus(id: String, status: DeliveryStatus)

    /** First listen of a voice message; later plays leave the timestamp alone. */
    @Query(
        "UPDATE message SET attachmentPlayedAtUnixMs = :ts " +
            "WHERE messageId = :id AND attachmentPlayedAtUnixMs IS NULL",
    )
    suspend fun markVoicePlayed(id: String, ts: Long)

    @Query("SELECT * FROM message WHERE messageId = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    /** The message only if it belongs to [cid]; inbound dedup is scoped per conversation. */
    @Query("SELECT * FROM message WHERE messageId = :id AND conversationId = :cid LIMIT 1")
    suspend fun getByIdInConversation(id: String, cid: String): MessageEntity?

    @Query(
        "SELECT messageId FROM message WHERE conversationId = :cid AND direction = 'INCOMING' AND status != 'READ'",
    )
    suspend fun selectUnreadIncomingIds(cid: String): List<String>

    /**
     * Unread incoming messages with whoever sent them, so a read receipt in a
     * group can be addressed to each sender instead of to "the" peer — a group
     * conversation has no single one.
     */
    @Query(
        """
        SELECT messageId, senderIdentityHash FROM message
        WHERE conversationId = :cid AND direction = 'INCOMING' AND status != 'READ'
        """,
    )
    suspend fun selectUnreadIncoming(cid: String): List<UnreadIncoming>

    @Query(
        """
        UPDATE message SET status = 'READ', readAtUnixMs = :ts
        WHERE conversationId = :cid AND direction = 'INCOMING' AND status != 'READ'
        """,
    )
    suspend fun markIncomingRead(cid: String, ts: Long)

    /**
     * The newest [limit] messages of a conversation, newest first, each carrying
     * the preview of the message it quotes.
     *
     * The UI renders this with `reverseLayout = true`, so index 0 is the newest
     * bubble and a new message never shifts the scroll anchor. Growing [limit]
     * (by 60 at a time) is how "load earlier" works; `(conversationId,
     * createdAtUnixMs)` is indexed so the window is a bounded index scan.
     * The `messageId` tiebreaker keeps the order total, which is what
     * [indexOf] counts against.
     */
    @Query(
        """
        SELECT m.*,
            r.body AS replyBody,
            r.attachmentName AS replyAttachmentName,
            r.contentType AS replyContentType,
            r.direction AS replyDirection,
            (
                SELECT o.lastError FROM outbox o
                WHERE o.messageId = m.messageId AND o.lastError IS NOT NULL LIMIT 1
            ) AS lastError,
            COALESCE(ct.displayName, gm.displayName) AS senderName
        FROM message m
        LEFT JOIN message r ON r.messageId = m.replyToMessageId AND r.conversationId = m.conversationId
        LEFT JOIN conversation cv ON cv.id = m.conversationId
        LEFT JOIN chat_group_member gm
            ON gm.groupId = cv.groupId AND gm.identityHash = m.senderIdentityHash
        LEFT JOIN contact ct
            ON m.senderIdentityHash IS NOT NULL
            AND lower(substr(hex(ct.identityHash), 1, 32)) = m.senderIdentityHash
        WHERE m.conversationId = :cid AND m.contentType != 'MESSAGE_CONTROL'
        ORDER BY m.createdAtUnixMs DESC, m.messageId DESC
        LIMIT :limit
        """,
    )
    fun observeConversation(cid: String, limit: Int): Flow<List<MessageWithReply>>

    @Query("SELECT COUNT(*) FROM message WHERE conversationId = :cid AND contentType != 'MESSAGE_CONTROL'")
    suspend fun countForConversation(cid: String): Int

    /**
     * Zero-based position of [messageId] in the same order [observeConversation]
     * uses, or -1 when the message is not in [cid]. Used to grow the window until
     * a quoted message is inside it before scrolling to it.
     *
     * The JOIN is what makes the missing case -1: with no anchor row the join
     * yields nothing, `COUNT(*)` is 0 and the query returns 0 - 1.
     */
    @Query(
        """
        SELECT COUNT(*) - 1 FROM message m
        JOIN message anchor ON anchor.messageId = :messageId AND anchor.conversationId = :cid
        WHERE m.conversationId = :cid
          AND m.contentType != 'MESSAGE_CONTROL'
          AND (
            m.createdAtUnixMs > anchor.createdAtUnixMs
            OR (m.createdAtUnixMs = anchor.createdAtUnixMs AND m.messageId >= anchor.messageId)
          )
        """,
    )
    suspend fun indexOf(cid: String, messageId: String): Int

    /** The newest message id of a conversation; used to repoint the chat-list preview. */
    @Query(
        """
        SELECT messageId FROM message
        WHERE conversationId = :cid AND contentType != 'MESSAGE_CONTROL'
        ORDER BY createdAtUnixMs DESC, messageId DESC LIMIT 1
        """,
    )
    suspend fun latestMessageId(cid: String): String?

    /** Stored attachment files of a conversation, so they can be erased with it. */
    @Query("SELECT attachmentPath FROM message WHERE conversationId = :cid AND attachmentPath IS NOT NULL")
    suspend fun attachmentPaths(cid: String): List<String>

    /** Delete-for-me: removes the local copy only, nothing is sent to the peer. */
    @Query("DELETE FROM message WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM message WHERE conversationId = :cid")
    suspend fun deleteByConversation(cid: String)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE nextAttemptUnixMs <= :now ORDER BY nextAttemptUnixMs ASC")
    suspend fun due(now: Long): List<OutboxEntity>

    /** Drops one recipient's pending delivery; the other members of a group keep theirs. */
    @Query("DELETE FROM outbox WHERE messageId = :messageId AND recipientIdentityHash = :recipient")
    suspend fun remove(messageId: String, recipient: String)

    /** Drops every recipient of a message (the message was deleted, or is fully resolved). */
    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    suspend fun removeAll(messageId: String)

    /** Drops every queued item of a conversation (contact deleted). */
    @Query("DELETE FROM outbox WHERE conversationId = :cid")
    suspend fun removeByConversation(cid: String)

    @Query("SELECT * FROM outbox WHERE messageId = :messageId")
    suspend fun forMessage(messageId: String): List<OutboxEntity>

    /** Makes every queued item immediately due (used on connectivity recovery). */
    @Query("UPDATE outbox SET nextAttemptUnixMs = 0")
    suspend fun resetBackoff()

    /**
     * Makes one queued item due right now and forgets its failure history
     * (manual "retry" on a message the user saw fail).
     */
    @Query(
        "UPDATE outbox SET nextAttemptUnixMs = 0, attemptCount = 0, lastError = NULL WHERE messageId = :messageId",
    )
    suspend fun resetBackoffFor(messageId: String)

    /** Members that still have this message queued; used to decide when nothing is left to wait for. */
    @Query("SELECT recipientIdentityHash FROM outbox WHERE messageId = :messageId")
    suspend fun pendingRecipients(messageId: String): List<String>

    @Update
    suspend fun update(item: OutboxEntity)
}
