package ir.vmessenger.data.repository

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.data.network.OutboxDispatcher
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.Conversation
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.MessageDirection
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.core.database.entity.DeliveryStatus as DbDeliveryStatus
import ir.vmessenger.core.database.entity.MessageDirection as DbMessageDirection

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val contactDao: ContactDao,
    private val outboxDispatcher: OutboxDispatcher,
) : ConversationRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        combine(
            conversationDao.observeAllWithPreview(),
            contactDao.observeContacts(),
        ) { conversations, contacts ->
            val contactMap = contacts.associateBy { it.id }
            conversations.map { row ->
                val conv = row.conversation
                val contact = contactMap[conv.contactId]
                Conversation(
                    id = conv.id,
                    contactId = conv.contactId,
                    contactName = contact?.displayName ?: conv.contactId,
                    lastMessagePreview = row.lastMessagePreview,
                    lastActivityUnixMs = conv.lastActivityUnixMs,
                    unreadCount = conv.unreadCount,
                )
            }
        }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeConversation(conversationId).map { messages ->
            messages.map { it.toDomain() }
        }

    override suspend fun getOrCreateConversation(contactId: String): String {
        val existing = conversationDao.getByContactId(contactId)
        if (existing != null) return existing.id
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        conversationDao.upsert(
            ConversationEntity(
                id = id,
                contactId = contactId,
                lastMessageId = null,
                lastActivityUnixMs = now,
                unreadCount = 0,
                muted = false,
            ),
        )
        return id
    }

    override suspend fun sendMessage(conversationId: String, text: String): AppResult<String> {
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        messageDao.insert(
            MessageEntity(
                messageId = messageId,
                conversationId = conversationId,
                direction = DbMessageDirection.OUTGOING,
                contentType = MessageContentType.TEXT,
                body = text,
                replyToMessageId = null,
                status = DbDeliveryStatus.QUEUED,
                createdAtUnixMs = now,
                sentAtUnixMs = null,
                deliveredAtUnixMs = null,
                readAtUnixMs = null,
            ),
        )
        AppLogger.info("Messaging", "outgoing chat queued messageId=$messageId conversation=$conversationId")
        outboxDao.enqueue(
            OutboxEntity(
                messageId = messageId,
                conversationId = conversationId,
                sealedPayload = null,
                attemptCount = 0,
                nextAttemptUnixMs = now,
                lastError = null,
            ),
        )
        conversationDao.getById(conversationId)?.let { conv ->
            conversationDao.update(
                conv.copy(
                    lastMessageId = messageId,
                    lastActivityUnixMs = now,
                ),
            )
        }
        // Delivery (and the SENT/FAILED status) is owned by the outbox dispatcher,
        // which retries until a transport send actually succeeds.
        outboxDispatcher.wake()
        return AppResult.Success(messageId)
    }

    override suspend fun markConversationRead(conversationId: String) = Unit

    private fun MessageEntity.toDomain() = ChatMessage(
        messageId = messageId,
        conversationId = conversationId,
        direction = when (direction) {
            DbMessageDirection.OUTGOING -> MessageDirection.OUTGOING
            DbMessageDirection.INCOMING -> MessageDirection.INCOMING
        },
        text = body.orEmpty(),
        status = when (status) {
            DbDeliveryStatus.QUEUED -> DeliveryStatus.QUEUED
            DbDeliveryStatus.SENT -> DeliveryStatus.SENT
            DbDeliveryStatus.DELIVERED -> DeliveryStatus.DELIVERED
            DbDeliveryStatus.READ -> DeliveryStatus.READ
            DbDeliveryStatus.FAILED -> DeliveryStatus.FAILED
        },
        createdAtUnixMs = createdAtUnixMs,
        replyToMessageId = replyToMessageId,
    )
}
