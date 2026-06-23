package ir.vmessenger.data.repository

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.Conversation
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.MessageDirection
import ir.vmessenger.domain.repository.ConversationRepository
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.core.database.entity.DeliveryStatus as DbDeliveryStatus
import ir.vmessenger.core.database.entity.MessageDirection as DbMessageDirection
import ir.vmessenger.core.proto.app.v1.ChatMessage as ProtoChatMessage

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val contactDao: ContactDao,
    private val identityRepository: IdentityRepository,
    private val messagingService: MessagingService,
) : ConversationRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        combine(
            conversationDao.observeAll(),
            contactDao.observeContacts(),
        ) { conversations, contacts ->
            val contactMap = contacts.associateBy { it.id }
            conversations.map { conv ->
                val contact = contactMap[conv.contactId]
                Conversation(
                    id = conv.id,
                    contactId = conv.contactId,
                    contactName = contact?.displayName ?: conv.contactId,
                    lastMessagePreview = null,
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
        val conv = conversationDao.getById(conversationId) ?: return AppResult.Success(messageId)
        val contact = contactDao.getById(conv.contactId)
        val identity = identityRepository.getIdentity()
        if (contact != null && identity != null) {
            val self = PeerIdentity(
                identityHash = identity.identityHash,
                ed25519PublicKey = identity.ed25519PublicKey,
                x25519StaticPublicKey = identity.x25519StaticPublicKey,
                ed25519PrivateKey = identityRepository.getEd25519PrivateKey(),
                x25519StaticPrivateKey = identityRepository.getX25519StaticPrivateKey(),
            )
            val peer = PeerIdentity(
                identityHash = contact.identityHash,
                ed25519PublicKey = contact.ed25519Public,
                x25519StaticPublicKey = contact.ed25519Public,
            )
            val envelope = MessageEnvelope.newBuilder()
                .setMessageId(ByteString.copyFromUtf8(messageId))
                .setSenderIdentityHash(ByteString.copyFrom(identity.identityHash))
                .setSentAtUnixMs(now)
                .setCounter(1)
                .setChat(ProtoChatMessage.newBuilder().setText(text))
                .build()
            messagingService.send(conv.contactId, self, peer, envelope)
            messageDao.markSent(messageId, DbDeliveryStatus.SENT, now)
        }
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
