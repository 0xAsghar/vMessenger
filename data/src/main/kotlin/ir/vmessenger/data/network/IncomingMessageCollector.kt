package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.Receipt
import ir.vmessenger.core.proto.app.v1.ReceiptType
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.IncomingEnvelope
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomingMessageCollector @Inject constructor(
    private val messagingService: MessagingService,
    private val identityRepository: IdentityRepository,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            messagingService.incoming.collect { incoming ->
                handleIncoming(incoming)
            }
        }
    }

    private suspend fun handleIncoming(incoming: IncomingEnvelope) {
        val envelope = incoming.envelope
        when {
            envelope.hasChat() -> persistChatMessage(incoming.contactId, envelope)
            envelope.hasReceipt() -> handleReceipt(envelope.receipt)
            else -> Unit
        }
    }

    private suspend fun persistChatMessage(contactId: String, envelope: MessageEnvelope) {
        val messageId = envelope.messageId.toStringUtf8()
        if (messageDao.getById(messageId) != null) return
        val conversationId = conversationDao.getByContactId(contactId)?.id
            ?: run {
                val now = System.currentTimeMillis()
                val id = java.util.UUID.randomUUID().toString()
                conversationDao.upsert(
                    ir.vmessenger.core.database.entity.ConversationEntity(
                        id = id,
                        contactId = contactId,
                        lastMessageId = null,
                        lastActivityUnixMs = now,
                        unreadCount = 1,
                        muted = false,
                    ),
                )
                id
            }
        val now = System.currentTimeMillis()
        messageDao.insert(
            MessageEntity(
                messageId = messageId,
                conversationId = conversationId,
                direction = MessageDirection.INCOMING,
                contentType = MessageContentType.TEXT,
                body = envelope.chat.text,
                replyToMessageId = null,
                status = DeliveryStatus.DELIVERED,
                createdAtUnixMs = envelope.sentAtUnixMs,
                sentAtUnixMs = envelope.sentAtUnixMs,
                deliveredAtUnixMs = now,
                readAtUnixMs = null,
            ),
        )
        AppLogger.info("Messaging", "incoming chat messageId=$messageId contact=$contactId")
        sendDeliveryReceipt(contactId, messageId, now)
    }

    private suspend fun handleReceipt(receipt: Receipt) {
        val refId = receipt.refMessageId.toStringUtf8()
        val now = receipt.atUnixMs
        when (receipt.type) {
            ReceiptType.RECEIPT_TYPE_DELIVERED ->
                messageDao.markDelivered(refId, DeliveryStatus.DELIVERED, now)
            ReceiptType.RECEIPT_TYPE_READ ->
                messageDao.markRead(refId, DeliveryStatus.READ, now)
            else -> Unit
        }
    }

    private suspend fun sendDeliveryReceipt(contactId: String, messageId: String, now: Long) {
        val identity = identityRepository.getIdentity() ?: return
        val contact = contactDao.getById(contactId) ?: return
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
            x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(32),
        )
        val receiptEnvelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("receipt-$messageId"))
            .setSenderIdentityHash(ByteString.copyFrom(identity.identityHash))
            .setSentAtUnixMs(now)
            .setCounter(1)
            .setReceipt(
                Receipt.newBuilder()
                    .setRefMessageId(ByteString.copyFromUtf8(messageId))
                    .setType(ReceiptType.RECEIPT_TYPE_DELIVERED)
                    .setAtUnixMs(now),
            )
            .build()
        messagingService.send(contactId, self, peer, receiptEnvelope)
    }
}
