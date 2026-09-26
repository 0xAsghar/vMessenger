package ir.vmessenger.data.network

import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
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
// LongParameterList/TooManyFunctions: one collaborator and one step per inbound concern
// (policy, chat, attachments, group control, receipts, notification); grouping them would
// only hide the wiring this class exists to make explicit.
@Suppress("LongParameterList", "TooManyFunctions")
class IncomingMessageCollector @Inject constructor(
    private val messaging: MessagingPort,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val contactRequestHandler: ContactRequestHandler,
    private val conversationResolver: InboundConversationResolver,
    private val groupControlHandler: GroupControlHandler,
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
        scope.launch { groupControlHandler.eraseReviewLeftovers() }
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
        val kind = InboundKind.of(envelope)
        if (kind != null && !InboundPolicy.allows(contact, kind)) {
            AppLogger.warn("Messaging", "rejected ${kind.name} from non-approved contact=$contactId")
            return
        }
        // Tells the request-retry worker the peer has us, so it can stop re-sending. Only a frame
        // meant for us proves that: node exchange and mailbox sync follow every handshake, strangers
        // included, and counting them marked a contact as answered the first time anything reached
        // them — so a request that had not been delivered yet never was.
        if (contact != null && kind?.provesPeerHasUs == true) {
            runCatching { contactDao.touchLastSeen(contactId, System.currentTimeMillis()) }
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
            InboundKind.GROUP_CONTROL ->
                acknowledge(contactId, envelope, incoming.session) { groupControlHandler.handle(contactId, envelope) }
            InboundKind.MESSAGE_REVISION ->
                acknowledge(contactId, envelope, incoming.session) { routes.messageRevision(contactId, envelope) }
            InboundKind.PROFILE_UPDATE ->
                acknowledge(contactId, envelope, incoming.session) { routes.profileUpdate(contactId, envelope) }
            InboundKind.GPS_BUZZER ->
                acknowledge(contactId, envelope, incoming.session) { routes.gpsBuzzer(contactId, envelope) }
            // Not acknowledged: call signalling is only useful live, and a receipt for a ring that
            // has already been answered or given up on is noise the sender would act on.
            InboundKind.CALL_SIGNAL -> routes.callSignal(contactId, envelope)
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
        // Resolved first: a group message the sender may not write is dropped
        // before a conversation is created for it, and before it is acknowledged.
        val target = messageId.takeIf { it.isNotBlank() }
            ?.let { conversationResolver.resolve(contactId, envelope, now) }
            ?.takeUnless { isDuplicate(contactId, messageId, it.conversationId, now, session) }
            ?: return
        val expiresAt = envelope.expiresAtUnixMs.takeIf { it > 0 }
        if (expiresAt != null && expiresAt <= now) {
            // Already expired in flight (a slow hop or a mailbox replay). Acknowledge so the sender
            // stops re-sending, but never surface a message that was meant to be gone by now.
            receiptSender.enqueueDelivered(contactId, messageId, now, session)
            return
        }
        messageDao.insert(
            MessageEntity(
                messageId = messageId,
                conversationId = target.conversationId,
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
                senderIdentityHash = target.senderIdentityHash,
                expiresAtUnixMs = expiresAt,
            ),
        )
        bumpConversation(target.conversationId, messageId, now)
        AppLogger.info("Messaging", "incoming chat messageId=$messageId contact=$contactId")
        notifyIncomingChat(contactId, target, envelope.chat.text)
        receiptSender.enqueueDelivered(contactId, messageId, now, session)
    }

    /**
     * Membership changes are acknowledged like messages: without a receipt the sender's
     * outbox keeps re-sending the control until its wait budget runs out, reopening a
     * session every few seconds for a change that already landed.
     */
    private suspend fun acknowledge(
        contactId: String,
        envelope: MessageEnvelope,
        session: ActiveSecureSession?,
        apply: suspend () -> Unit,
    ) {
        apply()
        val messageId = envelope.messageId.toStringUtf8()
        if (messageId.isNotBlank()) {
            receiptSender.enqueueDelivered(contactId, messageId, System.currentTimeMillis(), session)
        }
    }

    private suspend fun bumpConversation(conversationId: String, messageId: String, now: Long) {
        val conversation = conversationDao.getById(conversationId) ?: return
        conversationDao.update(
            conversation.copy(
                lastMessageId = messageId,
                lastActivityUnixMs = now,
                unreadCount = conversation.unreadCount + 1,
            ),
        )
    }

    /**
     * Dedup is scoped to the sender's conversation. A re-delivery of a message
     * we already hold is re-acknowledged (the sender never saw our receipt);
     * the same id in another conversation is a collision and is dropped
     * without an ack so a peer cannot probe or shadow other people's ids.
     *
     * Dropping rather than storing is forced by the schema, not chosen here: `messageId` is the
     * primary key of `message`, so a colliding row cannot be written at all and acking one would
     * claim we kept something we did not. Making this per-conversation would mean moving that
     * primary key, which `message_recipient` and `outbox` are both keyed against. The exposure is
     * small enough to leave: the id is a UUID, so suppressing a *future* message would mean
     * predicting one, and replaying an id already seen only shadows a message that has arrived.
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
            // The receiver resolved (and authorized) the conversation when the
            // header arrived, so the completion already knows where it landed.
            val target = InboundTarget(done.conversationId, senderIdentityHash = null, groupName = done.groupName)
            notifyIncomingChat(done.contactId, target, "📎 ${done.fileName}")
            receiptSender.enqueueDelivered(done.contactId, done.messageId, System.currentTimeMillis(), session)
        }
    }

    /**
     * Skips muted conversations and the one currently open on screen. A group
     * notification is titled with the group and prefixed with the sender, the way
     * a group message reads everywhere else.
     */
    private suspend fun notifyIncomingChat(contactId: String, target: InboundTarget, text: String) {
        val muted = conversationDao.getById(target.conversationId)?.muted == true
        if (muted || ActiveConversationTracker.isActive(target.conversationId)) return
        val contact = contactDao.getById(contactId) ?: return
        val sender = contact.displayName.ifBlank { contact.userHash }
        runCatching {
            notifier.notify(
                senderName = target.groupName ?: sender,
                preview = if (target.groupName == null) text else "$sender: $text",
                conversationId = target.conversationId,
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
