package ir.vmessenger.data.repository

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.data.attachment.AttachmentFileStore
import ir.vmessenger.data.network.OutboxWaker
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The write half of [ConversationRepositoryImpl], kept apart from the
 * attachment machinery so every mutation can be unit-tested against DAO fakes
 * alone (the same split [ConversationReadMarker] uses for the read half).
 *
 * Nothing here sends: queueing a message and waking the dispatcher is the whole
 * contract, and delivery (with its SENT/FAILED status) stays owned by the outbox.
 */
@Singleton
class ConversationWriter @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val draftStore: ConversationDraftStore,
    private val attachmentFiles: AttachmentFileStore,
    private val outboxWaker: OutboxWaker,
) {
    /**
     * Stores an outgoing message, queues it for delivery, moves the conversation
     * to the top of the chat list and wakes the dispatcher.
     */
    suspend fun queue(message: MessageEntity) {
        messageDao.insert(message)
        outboxDao.enqueue(
            OutboxEntity(
                messageId = message.messageId,
                conversationId = message.conversationId,
                sealedPayload = null,
                attemptCount = 0,
                nextAttemptUnixMs = message.createdAtUnixMs,
                lastError = null,
            ),
        )
        conversationDao.getById(message.conversationId)?.let { conversation ->
            conversationDao.update(
                conversation.copy(
                    lastMessageId = message.messageId,
                    lastActivityUnixMs = message.createdAtUnixMs,
                ),
            )
        }
        outboxWaker.wake()
    }

    /** Queues a text message (optionally quoting [replyToMessageId]) and returns its id. */
    suspend fun sendText(conversationId: String, text: String, replyToMessageId: String?): String {
        val messageId = UUID.randomUUID().toString()
        queue(
            MessageEntity(
                messageId = messageId,
                conversationId = conversationId,
                direction = MessageDirection.OUTGOING,
                contentType = MessageContentType.TEXT,
                body = text,
                replyToMessageId = replyToMessageId,
                status = DeliveryStatus.QUEUED,
                createdAtUnixMs = System.currentTimeMillis(),
                sentAtUnixMs = null,
                deliveredAtUnixMs = null,
                readAtUnixMs = null,
            ),
        )
        // The composer is empty now; a stale draft must not come back on re-entry.
        draftStore.clear(conversationId)
        AppLogger.info(TAG, "outgoing chat queued messageId=$messageId conversation=$conversationId")
        return messageId
    }

    /**
     * Delete for me: the local copy (and its stored attachment) is erased, the
     * queued send is dropped and the chat-list preview is repointed at whatever
     * is now newest. Nothing is sent to the peer, whose copy stays.
     */
    suspend fun deleteMessageForMe(messageId: String) {
        val message = messageDao.getById(messageId) ?: return
        message.attachmentPath?.let(attachmentFiles::delete)
        outboxDao.remove(messageId)
        messageDao.deleteById(messageId)
        val conversation = conversationDao.getById(message.conversationId)
        if (conversation?.lastMessageId == messageId) {
            conversationDao.setLastMessageId(conversation.id, messageDao.latestMessageId(conversation.id))
        }
        AppLogger.info(TAG, "message deleted locally messageId=$messageId")
    }

    /** Erases the conversation with its messages, attachment files, queued sends and draft. */
    suspend fun deleteConversation(conversationId: String) {
        for (path in messageDao.attachmentPaths(conversationId)) {
            attachmentFiles.delete(path)
        }
        outboxDao.removeByConversation(conversationId)
        // Messages cascade with the conversation row.
        conversationDao.deleteById(conversationId)
        draftStore.clear(conversationId)
        AppLogger.info(TAG, "conversation deleted id=$conversationId")
    }

    suspend fun setMuted(conversationId: String, muted: Boolean) = conversationDao.setMuted(conversationId, muted)

    /**
     * A message whose retry window expired has no outbox row left (the dispatcher
     * removed it when it gave up), so a manual retry re-queues it from scratch;
     * one that is still queued only has its backoff and failure history cleared.
     * A message already SENT keeps its status: it is waiting for a receipt, not failing.
     */
    suspend fun retry(messageId: String) {
        val message = messageDao.getById(messageId) ?: return
        if (outboxDao.getByMessageId(messageId) == null) {
            outboxDao.enqueue(
                OutboxEntity(
                    messageId = messageId,
                    conversationId = message.conversationId,
                    sealedPayload = null,
                    attemptCount = 0,
                    nextAttemptUnixMs = System.currentTimeMillis(),
                    lastError = null,
                ),
            )
        } else {
            outboxDao.resetBackoffFor(messageId)
        }
        if (message.status == DeliveryStatus.FAILED) {
            messageDao.updateStatus(messageId, DeliveryStatus.QUEUED)
        }
        AppLogger.info(TAG, "retry requested messageId=$messageId")
        outboxWaker.wake()
    }

    fun observeDraft(conversationId: String): Flow<String> = draftStore.observe(conversationId)

    suspend fun saveDraft(conversationId: String, text: String) = draftStore.save(conversationId, text)

    private companion object {
        const val TAG = "Messaging"
    }
}
