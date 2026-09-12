package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.MessageRecipientDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.MessageRecipientEntity
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
@Suppress("LongParameterList", "TooManyFunctions") // one DAO per table written, one method per mutation
class ConversationWriter @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val recipientDao: MessageRecipientDao,
    private val recipientResolver: MessageRecipientResolver,
    private val draftStore: ConversationDraftStore,
    private val attachmentFiles: AttachmentFileStore,
    private val outboxWaker: OutboxWaker,
) {
    /**
     * Stores an outgoing message, queues one delivery per recipient, moves the
     * conversation to the top of the chat list and wakes the dispatcher.
     *
     * A group message fans out to every member we can reach; a 1:1 message is the
     * single-recipient case of the same thing. With no reachable recipient the
     * message is stored FAILED rather than queued forever, so the user sees why
     * instead of watching a clock spin.
     */
    suspend fun queue(message: MessageEntity): AppResult<String> {
        val recipients = recipientResolver.resolve(message.conversationId)
        if (recipients.isEmpty()) {
            messageDao.insert(message.copy(status = DeliveryStatus.FAILED))
            touchConversation(message)
            AppLogger.warn(TAG, "no reachable recipient for messageId=${message.messageId}")
            return AppResult.Error(AppError.NoReachableMembers)
        }
        messageDao.insert(message)
        recipientDao.insertAll(recipients.map { queuedRecipient(message.messageId, it) })
        for (recipient in recipients) {
            outboxDao.enqueue(
                OutboxEntity(
                    messageId = message.messageId,
                    recipientIdentityHash = recipient,
                    conversationId = message.conversationId,
                    envelopeBytes = null,
                    attemptCount = 0,
                    nextAttemptUnixMs = message.createdAtUnixMs,
                    lastError = null,
                ),
            )
        }
        touchConversation(message)
        outboxWaker.wake()
        return AppResult.Success(message.messageId)
    }

    private suspend fun touchConversation(message: MessageEntity) {
        conversationDao.getById(message.conversationId)?.let { conversation ->
            conversationDao.update(
                conversation.copy(
                    lastMessageId = message.messageId,
                    lastActivityUnixMs = message.createdAtUnixMs,
                ),
            )
        }
    }

    /** Queues a text message (optionally quoting [replyToMessageId]) and returns its id. */
    suspend fun sendText(conversationId: String, text: String, replyToMessageId: String?): AppResult<String> {
        val messageId = UUID.randomUUID().toString()
        val result = queue(
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
        return result
    }

    /**
     * Stores a membership change as a system line in the group's conversation.
     * Nothing is queued: either the control came from a peer, or the caller queues
     * its outgoing copy with [queueGroupControl].
     */
    suspend fun recordGroupEvent(conversationId: String, text: String): String {
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        messageDao.insert(groupEvent(messageId, conversationId, text, now))
        conversationDao.getById(conversationId)?.let {
            conversationDao.update(it.copy(lastMessageId = messageId, lastActivityUnixMs = now))
        }
        return messageId
    }

    /**
     * Stores a membership change we made and queues the control envelope to every
     * recipient.
     *
     * The envelope is stored on the queue row rather than rebuilt at send time: it
     * is a snapshot of the group *at this version*, and by the time a retry runs
     * the group may already have moved on. Recipients are passed in because the
     * caller knows who the change is addressed to — a REMOVE has to reach the
     * member being removed, who is no longer in the membership.
     */
    suspend fun queueGroupControl(
        conversationId: String,
        text: String,
        envelope: ByteArray,
        recipients: List<String>,
    ): String {
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        messageDao.insert(groupEvent(messageId, conversationId, text, now))
        recipientDao.insertAll(recipients.map { queuedRecipient(messageId, it) })
        for (recipient in recipients) {
            outboxDao.enqueue(
                OutboxEntity(
                    messageId = messageId,
                    recipientIdentityHash = recipient,
                    conversationId = conversationId,
                    envelopeBytes = envelope,
                    attemptCount = 0,
                    nextAttemptUnixMs = now,
                    lastError = null,
                ),
            )
        }
        conversationDao.getById(conversationId)?.let {
            conversationDao.update(it.copy(lastMessageId = messageId, lastActivityUnixMs = now))
        }
        outboxWaker.wake()
        return messageId
    }

    /**
     * A system line: READ on arrival (it is not a message anyone has to open) and
     * INCOMING whoever made the change, because it renders centred either way and
     * an outgoing one would otherwise grow delivery ticks.
     */
    private fun groupEvent(messageId: String, conversationId: String, text: String, now: Long) = MessageEntity(
        messageId = messageId,
        conversationId = conversationId,
        direction = MessageDirection.INCOMING,
        contentType = MessageContentType.GROUP_CONTROL,
        body = text,
        replyToMessageId = null,
        status = DeliveryStatus.READ,
        createdAtUnixMs = now,
        sentAtUnixMs = now,
        deliveredAtUnixMs = now,
        readAtUnixMs = now,
    )

    /**
     * Delete for me: the local copy (and its stored attachment) is erased, the
     * queued send is dropped and the chat-list preview is repointed at whatever
     * is now newest. Nothing is sent to the peer, whose copy stays.
     */
    suspend fun deleteMessageForMe(messageId: String) {
        val message = messageDao.getById(messageId) ?: return
        message.attachmentPath?.let(attachmentFiles::delete)
        outboxDao.removeAll(messageId)
        // The recipient rows cascade with the message row.
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
        val missing = missingRecipients(message)
        for (recipient in missing) {
            outboxDao.enqueue(
                OutboxEntity(
                    messageId = messageId,
                    recipientIdentityHash = recipient,
                    conversationId = message.conversationId,
                    envelopeBytes = null,
                    attemptCount = 0,
                    nextAttemptUnixMs = System.currentTimeMillis(),
                    lastError = null,
                ),
            )
        }
        outboxDao.resetBackoffFor(messageId)
        if (message.status == DeliveryStatus.FAILED) {
            messageDao.updateStatus(messageId, DeliveryStatus.QUEUED)
        }
        AppLogger.info(TAG, "retry requested messageId=$messageId recipients=${missing.size}")
        outboxWaker.wake()
    }

    /**
     * Recipients a retry has to re-queue: those with nothing in flight. One still
     * waiting for a receipt only gets its backoff cleared, so a retry never
     * becomes a duplicate send. A message that failed with no recipients at all
     * (nobody was reachable when it was written) is resolved again from scratch,
     * and its delivery rows are created now.
     */
    private suspend fun missingRecipients(message: MessageEntity): List<String> {
        val pending = outboxDao.pendingRecipients(message.messageId).toSet()
        val known = recipientDao.forMessage(message.messageId)
        if (known.isEmpty()) {
            val resolved = recipientResolver.resolve(message.conversationId)
            recipientDao.insertAll(resolved.map { queuedRecipient(message.messageId, it) })
            return resolved
        }
        return known
            .filter { it.status != DeliveryStatus.DELIVERED && it.status != DeliveryStatus.READ }
            .map { it.identityHash }
            .filterNot { it in pending }
    }

    private fun queuedRecipient(messageId: String, identityHash: String) = MessageRecipientEntity(
        messageId = messageId,
        identityHash = identityHash,
        status = DeliveryStatus.QUEUED,
        sentAtUnixMs = null,
        deliveredAtUnixMs = null,
        readAtUnixMs = null,
    )

    fun observeDraft(conversationId: String): Flow<String> = draftStore.observe(conversationId)

    suspend fun saveDraft(conversationId: String, text: String) = draftStore.save(conversationId, text)

    private companion object {
        const val TAG = "Messaging"
    }
}
