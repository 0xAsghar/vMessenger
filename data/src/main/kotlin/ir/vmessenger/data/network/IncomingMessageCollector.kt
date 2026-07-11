package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.notifications.MessageNotificationManager
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.Receipt
import ir.vmessenger.core.proto.app.v1.ReceiptType
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.IncomingEnvelope
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import ir.vmessenger.network.messaging.PeerRelayService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.core.proto.app.v1.ChatMessage as ProtoChatMessage

@Singleton
class IncomingMessageCollector @Inject constructor(
    private val messagingService: MessagingService,
    private val identityRepository: IdentityRepository,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val peerExchangeService: PeerExchangeService,
    private val mailboxService: MailboxService,
    private val mailboxProtocolService: MailboxProtocolService,
    private val mailboxSyncService: MailboxSyncService,
    private val peerRelayForwarder: PeerRelayForwarder,
    private val peerRelayService: PeerRelayService,
    private val contactRequestHandler: ContactRequestHandler,
    private val locationSharingCoordinator: LocationSharingCoordinator,
    private val messageNotificationManager: MessageNotificationManager,
    private val privacyPreferences: PrivacyPreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        locationSharingCoordinator.start()
        scope.launch {
            messagingService.incoming.collect { incoming ->
                handleIncoming(incoming)
            }
        }
    }

    private suspend fun handleIncoming(incoming: IncomingEnvelope) {
        val envelope = incoming.envelope
        when {
            envelope.hasContactRequest() ->
                contactRequestHandler.handleRequest(
                    incoming.contactId,
                    envelope,
                    incoming.session?.peer,
                )
            envelope.hasContactResponse() ->
                contactRequestHandler.handleResponse(incoming.contactId, envelope)
            envelope.hasChat() -> {
                if (isApprovedContact(incoming.contactId)) {
                    persistChatMessage(incoming.contactId, envelope)
                } else {
                    AppLogger.warn("Messaging", "rejected chat from non-approved contact=${incoming.contactId}")
                }
            }
            envelope.hasLocation() -> {
                if (isApprovedContact(incoming.contactId)) {
                    locationSharingCoordinator.handleIncomingLocation(incoming.contactId, envelope)
                }
            }
            envelope.hasControl() -> {
                if (isApprovedContact(incoming.contactId)) {
                    locationSharingCoordinator.handleIncomingControl(incoming.contactId, envelope)
                }
            }
            envelope.hasReceipt() -> handleReceipt(envelope.receipt)
            envelope.hasNetworkNodes() -> peerExchangeService.ingestFromEnvelope(envelope)
            envelope.hasMailboxBlob() -> mailboxService.storeIncoming(envelope.mailboxBlob)
            envelope.hasRelayOpen() -> peerRelayForwarder.handleOpen(incoming)
            envelope.hasRelayData() -> peerRelayForwarder.handleData(incoming)
            envelope.hasRelayClose() -> peerRelayService.handleRelayClose(envelope)
            envelope.hasMailboxPut() ||
                envelope.hasMailboxList() ||
                envelope.hasMailboxFetch() ||
                envelope.hasMailboxDelete() ->
                mailboxProtocolService.handleIncoming(envelope, incoming.session)
            envelope.hasMailboxListResponse() ||
                envelope.hasMailboxFetchResponse() ->
                mailboxSyncService.handleResponse(envelope, incoming.session)
            else -> Unit
        }
    }

    private suspend fun isApprovedContact(contactId: String): Boolean {
        if (contactId.startsWith("stranger:")) return false
        val contact = contactDao.getById(contactId) ?: return false
        return contact.relationshipStatus == ContactRelationshipStatus.APPROVED
    }

    private suspend fun persistChatMessage(contactId: String, envelope: MessageEnvelope) {
        val messageId = envelope.messageId.toStringUtf8()
        if (messageDao.getById(messageId) != null) return
        val now = System.currentTimeMillis()
        val activityMs = envelope.sentAtUnixMs
        val existingConversation = conversationDao.getByContactId(contactId)
        val conversationId = existingConversation?.id
            ?: run {
                val id = java.util.UUID.randomUUID().toString()
                conversationDao.upsert(
                    ConversationEntity(
                        id = id,
                        contactId = contactId,
                        lastMessageId = messageId,
                        lastActivityUnixMs = activityMs,
                        unreadCount = 1,
                        muted = false,
                    ),
                )
                id
            }
        messageDao.insert(
            MessageEntity(
                messageId = messageId,
                conversationId = conversationId,
                direction = MessageDirection.INCOMING,
                contentType = MessageContentType.TEXT,
                body = envelope.chat.text,
                replyToMessageId = null,
                status = DeliveryStatus.DELIVERED,
                createdAtUnixMs = activityMs,
                sentAtUnixMs = envelope.sentAtUnixMs,
                deliveredAtUnixMs = now,
                readAtUnixMs = null,
            ),
        )
        if (existingConversation != null) {
            conversationDao.update(
                existingConversation.copy(
                    lastMessageId = messageId,
                    lastActivityUnixMs = activityMs,
                    unreadCount = existingConversation.unreadCount + 1,
                ),
            )
        }
        AppLogger.info("Messaging", "incoming chat messageId=$messageId contact=$contactId")
        notifyIncomingChat(contactId, conversationId, envelope.chat.text)
        sendDeliveryReceipt(contactId, messageId, now)
    }

    private suspend fun notifyIncomingChat(contactId: String, conversationId: String, text: String) {
        runCatching {
            val contact = contactDao.getById(contactId)
            val hideContent = privacyPreferences.hideNotificationContent.first()
            messageNotificationManager.showMessageNotification(
                senderName = contact?.let { c -> c.displayName.ifBlank { c.userHash } } ?: "مخاطب",
                preview = text,
                conversationId = conversationId,
                hideContent = hideContent,
            )
        }.onFailure { AppLogger.warn("Messaging", "notification failed: ${it.message}") }
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
