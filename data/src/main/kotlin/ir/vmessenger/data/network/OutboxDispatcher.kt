package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPath
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.MessageRecipientDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.core.proto.app.v1.ChatMessage as ProtoChatMessage

/**
 * Stable, developer-facing codes persisted in `outbox.lastError` and surfaced to
 * the UI as `ChatMessage.lastError`.
 *
 * They are codes, not sentences: the user-facing Persian text is chosen in the
 * UI layer, so nothing here is ever shown as-is.
 */
object OutboxError {
    const val PEER_PROTOCOL_OUTDATED = "peer_protocol_outdated"
    const val PEER_KEY_CHANGED = "peer_key_changed"
    const val ENDPOINT_NOT_FOUND = "endpoint_not_found"
    const val NETWORK_UNAVAILABLE = "network_unavailable"
    const val SEND_FAILED = "send_failed"
    const val MAILBOX_HANDOFF = "mailbox_handoff"
    const val CONTACT_MISSING = "contact_missing"
    const val CONTACT_BLOCKED = "contact_blocked"
    const val CONTACT_NOT_APPROVED = "contact_not_approved"

    /** They approved us once and then removed us; not the same as never having accepted. */
    const val CONTACT_REVOKED = "contact_revoked"
}

/**
 * The failure the user should be told about, by [AppError] *type* — never by
 * matching its message, which is a developer string.
 */
internal fun AppError.toOutboxErrorCode(): String = when (this) {
    is AppError.ProtocolVersion -> OutboxError.PEER_PROTOCOL_OUTDATED
    is AppError.Security -> OutboxError.PEER_KEY_CHANGED
    is AppError.NotFound -> OutboxError.ENDPOINT_NOT_FOUND
    is AppError.Network -> OutboxError.NETWORK_UNAVAILABLE
    else -> OutboxError.SEND_FAILED
}

/**
 * The slice of [OutboxDispatcher] the repository needs, as an interface so
 * queueing a message is testable without a transport.
 */
interface OutboxWaker {
    /** Trigger an immediate drain (a new message was queued, or a peer just connected). */
    fun wake()
}

/**
 * Drains the outbox and retries undelivered messages with exponential backoff.
 *
 * The previous behaviour attempted a single inline send when the user pressed
 * "send"; if that attempt failed (e.g. the peer endpoint was not yet resolvable
 * or the peer keys had not been learned) the message was lost forever because
 * nothing ever retried the outbox. This dispatcher is the single owner of
 * outbound delivery: messages stay queued until a transport send succeeds.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions")
class OutboxDispatcher @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val outboxDao: OutboxDao,
    private val recipientDao: MessageRecipientDao,
    private val deliveryAggregator: DeliveryAggregator,
    private val selfIdentityCache: SelfIdentityCache,
    private val messagingService: MessagingService,
    private val mailboxService: MailboxService,
    private val attachmentSender: AttachmentSender,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : OutboxWaker {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val parallelism = Semaphore(MAX_PARALLEL_CONVERSATIONS)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                runCatching { drainOnce() }
                    .onFailure { AppLogger.warn("Outbox", "drain failed: ${it.message}") }
                withTimeoutOrNull(POLL_INTERVAL_MS) { wakeups.receive() }
            }
        }
    }

    override fun wake() {
        wakeups.trySend(Unit)
    }

    /**
     * Clears pending backoff so every queued message is retried immediately.
     * Called when connectivity is (re)established, so messages that piled up
     * while offline go out at once instead of waiting out their backoff timers.
     */
    fun retryNow() {
        scope.launch {
            runCatching { outboxDao.resetBackoff() }
                .onFailure { AppLogger.warn("Outbox", "resetBackoff failed: ${it.message}") }
            wake()
        }
    }

    /** Stops the drain loop (coordinator stop); queued rows stay in the outbox until the next [start]. */
    fun stop() {
        started = false
        scope.coroutineContext.cancelChildren()
    }

    /**
     * Due rows are processed per recipient: order is kept per (conversation,
     * recipient), but different recipients run concurrently (bounded by
     * [MAX_PARALLEL_CONVERSATIONS]) so one member of a group that takes the full
     * dial timeout never holds back delivery to the rest — which is the whole
     * reason group fan-out is queued per recipient rather than per message.
     */
    private suspend fun drainOnce() {
        val now = System.currentTimeMillis()
        val due = outboxDao.due(now)
        if (due.isEmpty()) return
        val self = selfIdentityCache.get() ?: return
        coroutineScope {
            due.groupBy { it.conversationId to it.recipientIdentityHash }.values.map { group ->
                async {
                    parallelism.withPermit {
                        for (item in group) processItem(item, self)
                    }
                }
            }.awaitAll()
        }
    }

    @Suppress("ReturnCount")
    private suspend fun processItem(item: OutboxEntity, self: PeerIdentity) {
        val message = messageDao.getById(item.messageId)
        if (message == null) {
            outboxDao.removeAll(item.messageId)
            return
        }
        // This recipient's own state, not the message's aggregate: in a group the
        // aggregate is still QUEUED while some members already have the message.
        val recipientStatus = recipientDao.forMessage(item.messageId)
            .firstOrNull { it.identityHash == item.recipientIdentityHash }
            ?.status
        if (recipientStatus == DeliveryStatus.DELIVERED || recipientStatus == DeliveryStatus.READ) {
            finish(item)
            return
        }
        val conversation = conversationDao.getById(item.conversationId)
        val contact = contactDao.getByRoutingKey(item.recipientIdentityHash)
        if (conversation == null || contact == null) {
            backoff(item, OutboxError.CONTACT_MISSING)
            return
        }
        val rejection = contact.sendRejection()
        if (rejection != null) {
            backoff(item, rejection)
            return
        }
        send(item, message, conversation, contact, self, recipientStatus == DeliveryStatus.SENT)
    }

    /** Why this contact cannot be sent to right now, or null when they can. */
    private fun ContactEntity.sendRejection(): String? = when {
        // Never send to a blocked contact; the row stays queued so unblocking resumes delivery.
        blocked -> OutboxError.CONTACT_BLOCKED
        // Told apart, because "they have not accepted you yet" and "they accepted you and then
        // removed you" are different facts and the user was being given the first for both.
        relationshipStatus == ContactRelationshipStatus.REJECTED -> OutboxError.CONTACT_REVOKED
        relationshipStatus != ContactRelationshipStatus.APPROVED -> OutboxError.CONTACT_NOT_APPROVED
        else -> null
    }

    @Suppress("LongParameterList") // the send needs the queue row, the message, both ends and the wait state
    private suspend fun send(
        item: OutboxEntity,
        message: MessageEntity,
        conversation: ConversationEntity,
        contact: ContactEntity,
        self: PeerIdentity,
        awaitingReceipt: Boolean,
    ) {
        val peer = PeerIdentity(
            identityHash = contact.identityHash,
            ed25519PublicKey = contact.ed25519Public,
            x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(X25519_KEY_SIZE),
        )
        if (message.isAttachment()) {
            processAttachment(item, message, self, peer, contact.id, conversation.groupId)
        } else {
            sendChatMessage(item, message, self, peer, contact.id, conversation.groupId, awaitingReceipt)
        }
    }

    /**
     * Drops this recipient's queue row and refreshes the message's single status
     * from whatever the other recipients are doing.
     */
    private suspend fun finish(item: OutboxEntity) {
        outboxDao.remove(item.messageId, item.recipientIdentityHash)
        deliveryAggregator.recompute(item.messageId)
    }

    @Suppress("LongParameterList") // queue row, message, both ends, the chat's group and the wait state
    private suspend fun sendChatMessage(
        item: OutboxEntity,
        message: MessageEntity,
        self: PeerIdentity,
        peer: PeerIdentity,
        contactId: String,
        groupId: String?,
        awaitingReceipt: Boolean,
    ) {
        // Once transport-delivered we keep the row and re-send until a delivery
        // receipt arrives, so a lost receipt doesn't strand the message on "Sent".
        // The recipient dedups and re-acks, so no duplicate is shown.
        if (awaitingReceipt && item.receiptWaitCount >= MAX_RECEIPT_WAITS) {
            finish(item)
            AppLogger.info("Outbox", "receipt wait exhausted messageId=${message.messageId}, left as sent")
            return
        }
        val envelope = item.storedEnvelope() ?: buildChatEnvelope(message, self, groupId)
        // A receipt-wait re-send forces a fresh session: if the reused session had
        // silently died the message would otherwise vanish without another receipt.
        val result = messagingService.send(contactId, self, peer, envelope, forceReconnect = awaitingReceipt)
        when (result) {
            is AppResult.Success -> {
                if (!awaitingReceipt) markSent(item)
                rescheduleForReceipt(item, if (awaitingReceipt) item.receiptWaitCount + 1 else 0)
            }
            // Delivered once already: keep waiting for the ack rather than failing.
            is AppResult.Error ->
                if (awaitingReceipt) {
                    rescheduleForReceipt(item, item.receiptWaitCount + 1)
                } else {
                    AppLogger.warn("Outbox", "send failed messageId=${message.messageId}: ${result.error.message}")
                    backoff(item, result.error.toOutboxErrorCode(), peer, envelope, message.createdAtUnixMs)
                }
        }
    }

    /**
     * The exact envelope the queue row carries (a group control), or null when the
     * dispatcher should build one from the message row. A row whose stored bytes
     * no longer parse is treated as having none rather than failing the send: the
     * rebuild path still produces something deliverable.
     */
    private fun OutboxEntity.storedEnvelope(): MessageEnvelope? =
        envelopeBytes?.let { bytes ->
            runCatching { MessageEnvelope.parseFrom(bytes) }
                .onFailure { AppLogger.warn("Outbox", "stored envelope unreadable for messageId=$messageId") }
                .getOrNull()
        }

    /** Records that this recipient has the message on the wire, then refreshes the aggregate. */
    private suspend fun markSent(item: OutboxEntity) {
        val now = System.currentTimeMillis()
        recipientDao.advance(
            messageId = item.messageId,
            identityHash = item.recipientIdentityHash,
            status = DeliveryStatus.SENT,
            rank = DeliveryStatus.SENT.rank(),
            sentAt = now,
            deliveredAt = null,
            readAt = null,
        )
        deliveryAggregator.recompute(item.messageId)
        AppLogger.info("Outbox", "sent messageId=${item.messageId} to ${item.recipientIdentityHash}, awaiting receipt")
    }

    private suspend fun rescheduleForReceipt(item: OutboxEntity, waitCount: Int) {
        outboxDao.update(
            item.copy(
                attemptCount = 0,
                receiptWaitCount = waitCount,
                nextAttemptUnixMs = System.currentTimeMillis() + RECEIPT_WAIT_MS,
                lastError = null,
            ),
        )
    }

    /**
     * Attachments are transferred once per recipient (documented O(n) for a group:
     * there is no shared storage to upload to, so every member gets their own
     * encrypted stream over their own session).
     */
    @Suppress("LongParameterList") // queue row, message, both ends, the contact and the chat's group
    private suspend fun processAttachment(
        item: OutboxEntity,
        message: MessageEntity,
        self: PeerIdentity,
        peer: PeerIdentity,
        contactId: String,
        groupId: String?,
    ) {
        val path = message.attachmentPath
        val file = path?.let(::File)
        if (file == null || !file.exists()) {
            recipientDao.markFailed(item.messageId, item.recipientIdentityHash)
            finish(item)
            AppLogger.warn("Outbox", "attachment file missing for messageId=${item.messageId}")
            return
        }
        when (val result = attachmentSender.send(contactId, self, peer, message, file, groupId)) {
            is AppResult.Success -> {
                markSent(item)
                // Wait for the receipt, exactly as a chat message does. Finishing on the transport
                // write alone meant a receiver that ran out of disk, refused the transfer on quota,
                // or failed the SHA-256 check left the sender reading "sent" for a message that
                // does not exist on the other side. The receiver already acks a completed transfer.
                if (item.receiptWaitCount < MAX_RECEIPT_WAITS) {
                    rescheduleForReceipt(item, item.receiptWaitCount + 1)
                } else {
                    finish(item)
                    AppLogger.warn("Outbox", "attachment receipt never arrived messageId=${message.messageId}")
                }
                AppLogger.info("Outbox", "sent attachment messageId=${message.messageId}")
            }
            is AppResult.Error -> {
                AppLogger.warn("Outbox", "attachment failed messageId=${message.messageId}: ${result.error.message}")
                backoff(item, result.error.toOutboxErrorCode(), createdAtUnixMs = message.createdAtUnixMs)
            }
        }
    }

    @Suppress("LongParameterList")
    private suspend fun backoff(
        item: OutboxEntity,
        error: String?,
        peer: PeerIdentity? = null,
        envelope: MessageEnvelope? = null,
        createdAtUnixMs: Long = 0L,
    ) {
        val attempt = item.attemptCount + 1
        // With store-and-forward on, park a sealed copy in the mailbox once the
        // direct-retry budget is spent. The row stays QUEUED (never marked SENT
        // on a handoff) and direct delivery keeps being retried until a receipt
        // removes it or the retry window expires. Skipped when the peer's static
        // key is unknown, since there is nothing to seal to.
        val handedOff = attempt == MAX_ATTEMPTS &&
            P2PConfig.storeAndForwardEnabled &&
            peer != null &&
            envelope != null &&
            mailboxService.enqueueForRecipient(peer, envelope)
        if (handedOff) {
            NetworkPathTracker.record(NetworkPath.STORE_AND_FORWARD, "outbox-${item.messageId}")
            AppLogger.info("Outbox", "sealed copy parked in mailbox messageId=${item.messageId}; still retrying direct")
        }
        // Keep retrying (capped backoff) so a message to a temporarily offline
        // peer still delivers when they return; only give up after a long window
        // instead of dropping it after a handful of minutes.
        // Capped on attempts as well as age. The age is wall-clock arithmetic against the time the
        // message was composed, so an NTP correction or a user changing the date by more than a day
        // would otherwise mark every queued message failed at once — messages that were minutes
        // old and perfectly deliverable.
        val age = System.currentTimeMillis() - createdAtUnixMs
        val plausiblyExpired = createdAtUnixMs > 0 && age in RETRY_WINDOW_MS..MAX_PLAUSIBLE_AGE_MS
        val expired = plausiblyExpired || attempt >= MAX_ATTEMPTS_BEFORE_GIVING_UP
        if (expired) {
            // Only this recipient is given up on; the aggregate stays QUEUED/SENT
            // while other members still have a chance, and only turns FAILED when
            // every one of them has been given up on.
            recipientDao.markFailed(item.messageId, item.recipientIdentityHash)
            finish(item)
            AppLogger.warn("Outbox", "giving up messageId=${item.messageId} after retry window: $error")
            return
        }
        val backoffMs = (BASE_BACKOFF_MS shl minOf(attempt, MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
        outboxDao.update(
            item.copy(
                attemptCount = attempt,
                nextAttemptUnixMs = System.currentTimeMillis() + backoffMs,
                lastError = if (handedOff) OutboxError.MAILBOX_HANDOFF else error,
            ),
        )
        AppLogger.info("Outbox", "retry messageId=${item.messageId} attempt=$attempt in ${backoffMs}ms: $error")
    }

    private fun MessageEntity.isAttachment(): Boolean = when (contentType) {
        MessageContentType.IMAGE,
        MessageContentType.VIDEO,
        MessageContentType.FILE,
        MessageContentType.AUDIO,
        -> true
        else -> false
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_PARALLEL_CONVERSATIONS = 8
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_SHIFT = 5
        private const val MAX_ATTEMPTS = 12
        private const val X25519_KEY_SIZE = 32
        private const val RECEIPT_WAIT_MS = 15_000L
        private const val MAX_RECEIPT_WAITS = 4

        // Keep retrying an undelivered message this long (capped backoff) so it
        // arrives when a temporarily-offline peer returns, before giving up.
        private const val RETRY_WINDOW_MS = 24 * 60 * 60_000L

        /** Beyond this an "age" is a clock change, not a message that waited. */
        private const val MAX_PLAUSIBLE_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** The backstop for when the clock cannot be trusted: enough retries is enough. */
        private const val MAX_ATTEMPTS_BEFORE_GIVING_UP = 200
    }
}

/**
 * The wire form of a stored outgoing chat message.
 *
 * A quoted id travels as the UTF-8 bytes of our local message id, exactly like
 * `message_id`, so the peer can match the quote against the message we sent them;
 * it is left unset when the message is not a reply.
 */
internal fun buildChatEnvelope(message: MessageEntity, self: PeerIdentity, groupId: String?): MessageEnvelope {
    val chat = ProtoChatMessage.newBuilder().setText(message.body.orEmpty())
    message.replyToMessageId?.takeIf { it.isNotBlank() }?.let {
        chat.replyToMessageId = ByteString.copyFromUtf8(it)
    }
    return MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8(message.messageId))
        .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
        .setSentAtUnixMs(message.createdAtUnixMs)
        .setCounter(1)
        .applyGroup(groupId)
        .setChat(chat)
        .build()
}

/**
 * Tags the envelope with the group it belongs to. The value is the group id as
 * bytes, which is how the receiver looks the group up; a 1:1 envelope leaves the
 * field empty.
 */
internal fun MessageEnvelope.Builder.applyGroup(groupId: String?): MessageEnvelope.Builder =
    if (groupId.isNullOrBlank()) this else setGroupId(ByteString.copyFromUtf8(groupId))
