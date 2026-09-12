package ir.vmessenger.data.network

import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.notifications.ActiveConversationTracker
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.IncomingEnvelope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.core.proto.app.v1.ChatMessage as ProtoChatMessage

/**
 * Consumes authenticated inbound envelopes and enforces the sender policy
 * before anything is persisted or answered: every frame carries the contact
 * id the handshake resolved, and [InboundPolicy] decides what that contact
 * may send. Chat persistence, dedup, timestamps and notifications live here;
 * receipts, contact requests and the remaining envelope families are
 * delegated.
 *
 * Envelopes are handed to one worker per contact ([IncomingWorkerRouter]) so
 * a slow handler for one peer never delays the others, and handlers never
 * send inline: receipts go through [ReceiptSender], everything else through
 * the outbox.
 */
@Singleton
@Suppress("LongParameterList") // one collaborator per inbound concern; grouping them would only hide the wiring
class IncomingMessageCollector @Inject constructor(
    private val messaging: MessagingPort,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val contactRequestHandler: ContactRequestHandler,
    private val receiptHandler: InboundReceiptHandler,
    private val receiptSender: ReceiptSender,
    private val routes: InboundRoutes,
    private val notifier: IncomingMessageNotifier,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private var scope = newScope()
    private var router = IncomingWorkerRouter(ioDispatcher, ::handleIncoming)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (!scope.isActive) {
            scope = newScope()
            router = IncomingWorkerRouter(ioDispatcher, ::handleIncoming)
        }
        routes.start()
        receiptSender.start()
        // Envelopes are routed straight from the session read loop that
        // decrypted them: a full worker queue for one contact suspends that
        // session only, never the others. The flow only drains whatever
        // arrived before the sink was installed.
        val router = router
        messaging.setIncomingSink { incoming -> router.route(incoming) }
        scope.launch {
            messaging.incoming.collect { incoming ->
                router.route(incoming)
            }
        }
    }

    /**
     * Stops consuming and drops every queued envelope (secure wipe / coordinator
     * stop), and stops the workers the inbound path drives (receipts, attachment
     * pruning, location retention).
     */
    fun stop() {
        started = false
        messaging.setIncomingSink(null)
        router.stop()
        scope.cancel()
        receiptSender.stop()
        routes.stop()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler("Inbound"))

    /**
     * Entry point for one authenticated envelope. Also used for envelopes that
     * arrive outside a live session (mailbox delivery), with `session = null`.
     */
    suspend fun handleIncoming(incoming: IncomingEnvelope) {
        val envelope = incoming.envelope
        val contactId = incoming.contactId
        val contact = if (contactId.startsWith(STRANGER_PREFIX)) null else contactDao.getById(contactId)
        if (contact?.blocked == true) {
            AppLogger.warn("Messaging", "blocked contact frame dropped contact=$contactId")
            return
        }
        // Any inbound frame from a known contact proves the peer is reachable and
        // has us, so the request-retry worker can stop re-sending to them.
        if (contact != null) {
            runCatching { contactDao.touchLastSeen(contactId, System.currentTimeMillis()) }
        }
        val kind = InboundKind.of(envelope)
        if (kind != null && !InboundPolicy.allows(contact, kind)) {
            AppLogger.warn("Messaging", "rejected ${kind.name} from non-approved contact=$contactId")
            return
        }
        dispatch(kind, incoming)
    }

    private suspend fun dispatch(kind: InboundKind?, incoming: IncomingEnvelope) {
        val envelope = incoming.envelope
        val contactId = incoming.contactId
        when (kind) {
            InboundKind.CONTACT_REQUEST -> contactRequestHandler.handleRequest(envelope, incoming.session?.peer)
            InboundKind.CONTACT_RESPONSE ->
                contactRequestHandler.handleResponse(contactId, envelope, incoming.session?.peer)
            InboundKind.CHAT -> persistChatMessage(contactId, envelope, incoming.session)
            InboundKind.ATTACHMENT -> handleAttachmentEnvelope(contactId, envelope, incoming.session)
            InboundKind.LOCATION -> routes.location(contactId, envelope)
            InboundKind.CONTROL -> routes.control(contactId, envelope)
            InboundKind.RECEIPT -> receiptHandler.handle(contactId, envelope.receipt)
            InboundKind.NETWORK_NODES, null -> routes.infrastructure(incoming)
        }
    }

    private suspend fun persistChatMessage(
        contactId: String,
        envelope: MessageEnvelope,
        session: ActiveSecureSession?,
    ) {
        val messageId = envelope.messageId.toStringUtf8()
        val now = System.currentTimeMillis()
        val existing = conversationDao.getByContactId(contactId)
        if (messageId.isBlank() || isDuplicate(contactId, messageId, existing?.id, now, session)) return
        val conversationId = existing?.id ?: createConversation(contactId, messageId, now)
        messageDao.insert(
            MessageEntity(
                messageId = messageId,
                conversationId = conversationId,
                direction = MessageDirection.INCOMING,
                contentType = MessageContentType.TEXT,
                body = envelope.chat.text,
                replyToMessageId = envelope.chat.quotedMessageIdOrNull(),
                status = DeliveryStatus.DELIVERED,
                // Arrival order is ours; the peer's clock is only kept as a clamped hint.
                createdAtUnixMs = now,
                sentAtUnixMs = envelope.sentAtUnixMs.coerceIn(now - SENT_AT_MAX_PAST_MS, now + SENT_AT_MAX_FUTURE_MS),
                deliveredAtUnixMs = now,
                readAtUnixMs = null,
            ),
        )
        if (existing != null) {
            conversationDao.update(
                existing.copy(
                    lastMessageId = messageId,
                    lastActivityUnixMs = now,
                    unreadCount = existing.unreadCount + 1,
                ),
            )
        }
        AppLogger.info("Messaging", "incoming chat messageId=$messageId contact=$contactId")
        notifyIncomingChat(contactId, conversationId, envelope.chat.text)
        receiptSender.enqueueDelivered(contactId, messageId, now, session)
    }

    /**
     * Dedup is scoped to the sender's conversation. A re-delivery of a message
     * we already hold is re-acknowledged (the sender never saw our receipt);
     * the same id in another conversation is a collision and is dropped
     * without an ack so a peer cannot probe or shadow other people's ids.
     */
    private suspend fun isDuplicate(
        contactId: String,
        messageId: String,
        conversationId: String?,
        now: Long,
        session: ActiveSecureSession?,
    ): Boolean {
        val known = messageDao.getById(messageId) ?: return false
        if (known.conversationId == conversationId) {
            receiptSender.enqueueDelivered(contactId, messageId, now, session)
        } else {
            AppLogger.warn("Messaging", "messageId collision across conversations id=$messageId contact=$contactId")
        }
        return true
    }

    private suspend fun createConversation(contactId: String, messageId: String, now: Long): String {
        val id = UUID.randomUUID().toString()
        conversationDao.upsert(
            ConversationEntity(
                id = id,
                contactId = contactId,
                lastMessageId = messageId,
                lastActivityUnixMs = now,
                unreadCount = 1,
                muted = false,
            ),
        )
        return id
    }

    private suspend fun handleAttachmentEnvelope(
        contactId: String,
        envelope: MessageEnvelope,
        session: ActiveSecureSession?,
    ) {
        if (envelope.hasAttachmentInfo()) {
            if (routes.attachmentInfo(contactId, envelope)) {
                val messageId = envelope.messageId.toStringUtf8()
                receiptSender.enqueueDelivered(contactId, messageId, System.currentTimeMillis(), session)
            }
            return
        }
        routes.attachmentChunk(contactId, envelope)?.let { done ->
            val conversationId = conversationDao.getByContactId(done.contactId)?.id ?: done.messageId
            notifyIncomingChat(done.contactId, conversationId, "📎 ${done.fileName}")
            receiptSender.enqueueDelivered(done.contactId, done.messageId, System.currentTimeMillis(), session)
        }
    }

    /** Skips muted conversations and the one currently open on screen. */
    private suspend fun notifyIncomingChat(contactId: String, conversationId: String, text: String) {
        val muted = conversationDao.getById(conversationId)?.muted == true
        if (muted || ActiveConversationTracker.isActive(conversationId)) return
        val contact = contactDao.getById(contactId) ?: return
        runCatching {
            notifier.notify(
                senderName = contact.displayName.ifBlank { contact.userHash },
                preview = text,
                conversationId = conversationId,
            )
        }.onFailure { AppLogger.warn("Messaging", "notification failed: ${it.message}") }
    }

    companion object {
        private const val STRANGER_PREFIX = IncomingWorkerRouter.STRANGER_PREFIX
        private const val SENT_AT_MAX_PAST_MS = 7L * 24 * 60 * 60_000L
        private const val SENT_AT_MAX_FUTURE_MS = 5 * 60_000L
    }
}

/** Ours are UUID strings (36 chars); anything longer is a peer making things up. */
private const val MAX_MESSAGE_ID_LENGTH = 64

/**
 * The id of the message this one quotes, or null when it quotes nothing.
 *
 * The value is peer-controlled, so it is length-bounded before it reaches the
 * database; whether it actually resolves is decided by the reply JOIN, which
 * only matches ids inside the same conversation.
 */
internal fun ProtoChatMessage.quotedMessageIdOrNull(): String? =
    replyToMessageId.toStringUtf8()
        .takeIf { it.isNotBlank() && it.length <= MAX_MESSAGE_ID_LENGTH }
