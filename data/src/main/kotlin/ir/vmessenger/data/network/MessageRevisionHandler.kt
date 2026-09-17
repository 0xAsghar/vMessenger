package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.attachment.AttachmentFileStore
import javax.inject.Inject
import javax.inject.Singleton

/** Peer-supplied ids are bounded before they reach a query, as quoted reply ids already are. */
private const val MAX_MESSAGE_ID_CHARS = 64

/**
 * Edits and delete-for-everyone, applied to messages *we received*.
 *
 * Authority is ownership: a peer may only revise a message that is INCOMING in a conversation with
 * them, and in a group only one that carries their own routing key as its sender. Who the sender
 * is comes from the authenticated session, never from the envelope.
 *
 * A delete arriving before the message it deletes — mailbox replay, or an out-of-order session —
 * writes the tombstone anyway. The collector's dedup is scoped to (conversation, message id), so
 * the original then arrives, matches an id already present in that conversation, is acknowledged
 * and dropped. That is the whole out-of-order story; no separate tombstone table is needed.
 */
@Singleton
class MessageRevisionHandler @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val attachmentFiles: AttachmentFileStore,
) {
    suspend fun handle(contactId: String, envelope: MessageEnvelope) {
        val conversationId = conversationFor(contactId, envelope) ?: return
        when {
            envelope.hasMessageEdit() -> applyEdit(conversationId, contactId, envelope)
            envelope.hasMessageDelete() -> applyDelete(conversationId, contactId, envelope)
        }
    }

    private suspend fun applyEdit(conversationId: String, contactId: String, envelope: MessageEnvelope) {
        val edit = envelope.messageEdit
        val target = ownedTarget(conversationId, contactId, edit.targetMessageId.toStringUtf8())
        // Strictly newer, so a replayed or reordered edit cannot reinstate older text.
        if (target == null || edit.editedAtUnixMs <= (target.editedAtUnixMs ?: 0L)) return
        // A caption belongs to an attachment and the body to a text message; the two are never
        // both set, so writing back to whichever the row uses keeps the bubble intact.
        val revised = if (target.contentType == MessageContentType.TEXT) {
            target.copy(body = edit.newText, editedAtUnixMs = edit.editedAtUnixMs)
        } else {
            target.copy(caption = edit.newText, editedAtUnixMs = edit.editedAtUnixMs)
        }
        messageDao.update(revised)
    }

    private suspend fun applyDelete(conversationId: String, contactId: String, envelope: MessageEnvelope) {
        val targetId = envelope.messageDelete.targetMessageId.toStringUtf8()
        if (!targetId.isUsableId()) return
        val deletedAt = envelope.messageDelete.deletedAtUnixMs
        val existing = messageDao.getByIdInConversation(targetId, conversationId)
        if (existing == null) {
            insertEarlyTombstone(conversationId, targetId, deletedAt)
            return
        }
        if (isOwnedBy(existing, contactId)) {
            existing.attachmentPath?.let { attachmentFiles.delete(it) }
            messageDao.update(tombstone(existing, deletedAt))
        }
    }

    /** The row a peer is allowed to revise, or null — bad id, already a tombstone, or not theirs. */
    private suspend fun ownedTarget(conversationId: String, contactId: String, targetId: String): MessageEntity? {
        if (!targetId.isUsableId()) return null
        return messageDao.getByIdInConversation(targetId, conversationId)
            ?.takeIf { it.contentType != MessageContentType.DELETED && isOwnedBy(it, contactId) }
    }

    /**
     * In a 1:1 thread, INCOMING already means "from them". In a group everyone's messages share
     * one conversation, so the row has to name this peer as its sender or a member could revise
     * anybody's words.
     */
    private suspend fun isOwnedBy(target: MessageEntity, contactId: String): Boolean {
        if (target.direction != MessageDirection.INCOMING) {
            AppLogger.warn("Messaging", "peer tried to revise a message we sent contact=$contactId")
            return false
        }
        val sender = target.senderIdentityHash
        val contactKey = contactDao.getById(contactId)
            ?.let { IdentityHashMatcher.routingKeyHex(it.identityHash) }
        return sender == null || sender == contactKey
    }

    private fun String.isUsableId(): Boolean = isNotBlank() && length <= MAX_MESSAGE_ID_CHARS

    /** [deletedAtUnixMs] is the sender's, as the delete carried it; 0 from a sender that set none. */
    private fun tombstone(target: MessageEntity, deletedAtUnixMs: Long) = target.copy(
        contentType = MessageContentType.DELETED,
        deletedAtUnixMs = deletedAtUnixMs.takeIf { it > 0 },
        body = null,
        caption = null,
        replyToMessageId = null,
        attachmentName = null,
        attachmentMimeType = null,
        attachmentSizeBytes = null,
        attachmentPath = null,
        attachmentSha256 = null,
        attachmentWaveform = null,
        attachmentDurationMs = null,
    )

    private suspend fun insertEarlyTombstone(conversationId: String, targetId: String, deletedAtUnixMs: Long) {
        AppLogger.info("Messaging", "delete arrived before its message id=$targetId")
        messageDao.insert(
            MessageEntity(
                messageId = targetId,
                conversationId = conversationId,
                direction = MessageDirection.INCOMING,
                contentType = MessageContentType.DELETED,
                body = null,
                replyToMessageId = null,
                status = DeliveryStatus.DELIVERED,
                createdAtUnixMs = deletedAtUnixMs,
                sentAtUnixMs = deletedAtUnixMs,
                deliveredAtUnixMs = deletedAtUnixMs,
                readAtUnixMs = null,
                deletedAtUnixMs = deletedAtUnixMs.takeIf { it > 0 },
            ),
        )
    }

    /**
     * Never creates a conversation. The generic resolver fabricates a 1:1 thread when none exists,
     * which for a revision would mean conjuring an empty chat out of a control frame.
     */
    private suspend fun conversationFor(contactId: String, envelope: MessageEnvelope): String? {
        val conversation = if (envelope.groupId.isEmpty) {
            conversationDao.getByContactId(contactId)
        } else {
            GroupControlCodec.groupIdOf(envelope)?.let { conversationDao.getByGroupId(it) }
        }
        return conversation?.id
    }
}
