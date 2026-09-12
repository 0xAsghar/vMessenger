package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPath
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.entity.DeliveryStatus
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
    private val selfIdentityCache: SelfIdentityCache,
    private val messagingService: MessagingService,
    private val mailboxService: MailboxService,
    private val attachmentSender: AttachmentSender,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
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

    /** Trigger an immediate drain (e.g. a new message was queued or a peer just connected). */
    fun wake() {
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
     * Due rows are processed per conversation: order is kept within a
     * conversation, but conversations run concurrently (bounded by
     * [MAX_PARALLEL_CONVERSATIONS]) so a contact that takes the full dial
     * timeout never holds back a message to a reachable one.
     */
    private suspend fun drainOnce() {
        val now = System.currentTimeMillis()
        val due = outboxDao.due(now)
        if (due.isEmpty()) return
        val self = selfIdentityCache.get() ?: return
        coroutineScope {
            due.groupBy { it.conversationId }.values.map { group ->
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
            outboxDao.remove(item.messageId)
            return
        }
        if (message.status == DeliveryStatus.DELIVERED || message.status == DeliveryStatus.READ) {
            outboxDao.remove(item.messageId)
            return
        }
        val conversation = conversationDao.getById(item.conversationId)
        val contact = conversation?.let { contactDao.getById(it.contactId) }
        if (conversation == null || contact == null) {
            backoff(item, "contact missing")
            return
        }
        // Never send to a blocked contact; the row stays queued so unblocking resumes delivery.
        if (contact.blocked) {
            backoff(item, "contact blocked")
            return
        }
        if (contact.relationshipStatus != ir.vmessenger.core.database.entity.ContactRelationshipStatus.APPROVED) {
            backoff(item, "contact not approved")
            return
        }
        val peer = PeerIdentity(
            identityHash = contact.identityHash,
            ed25519PublicKey = contact.ed25519Public,
            x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(X25519_KEY_SIZE),
        )
        if (message.isAttachment()) {
            processAttachment(item, message, self, peer, conversation.contactId)
        } else {
            sendChatMessage(item, message, self, peer, conversation.contactId)
        }
    }

    private suspend fun sendChatMessage(
        item: OutboxEntity,
        message: MessageEntity,
        self: PeerIdentity,
        peer: PeerIdentity,
        contactId: String,
    ) {
        // Once transport-delivered (status SENT) we keep the row and re-send until
        // a delivery receipt arrives, so a lost receipt doesn't strand the message
        // on "Sent". The recipient dedups and re-acks, so no duplicate is shown.
        val awaitingReceipt = message.status == DeliveryStatus.SENT
        if (awaitingReceipt && item.receiptWaitCount >= MAX_RECEIPT_WAITS) {
            outboxDao.remove(item.messageId)
            AppLogger.info("Outbox", "receipt wait exhausted messageId=${message.messageId}, left as sent")
            return
        }
        val envelope = buildEnvelope(message, self)
        // A receipt-wait re-send forces a fresh session: if the reused session had
        // silently died the message would otherwise vanish without another receipt.
        val result = messagingService.send(contactId, self, peer, envelope, forceReconnect = awaitingReceipt)
        when (result) {
            is AppResult.Success -> {
                if (!awaitingReceipt) {
                    messageDao.markSent(message.messageId, DeliveryStatus.SENT, System.currentTimeMillis())
                    AppLogger.info("Outbox", "sent messageId=${message.messageId}, awaiting receipt")
                }
                rescheduleForReceipt(item, if (awaitingReceipt) item.receiptWaitCount + 1 else 0)
            }
            // Delivered once already: keep waiting for the ack rather than failing.
            is AppResult.Error ->
                if (awaitingReceipt) {
                    rescheduleForReceipt(item, item.receiptWaitCount + 1)
                } else {
                    backoff(item, result.error.message, peer, envelope, message.createdAtUnixMs)
                }
        }
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

    private suspend fun processAttachment(
        item: OutboxEntity,
        message: MessageEntity,
        self: PeerIdentity,
        peer: PeerIdentity,
        contactId: String,
    ) {
        val path = message.attachmentPath
        val file = path?.let(::File)
        if (file == null || !file.exists()) {
            messageDao.updateStatus(item.messageId, DeliveryStatus.FAILED)
            outboxDao.remove(item.messageId)
            AppLogger.warn("Outbox", "attachment file missing for messageId=${item.messageId}")
            return
        }
        when (val result = attachmentSender.send(contactId, self, peer, message, file)) {
            is AppResult.Success -> {
                messageDao.markSent(message.messageId, DeliveryStatus.SENT, System.currentTimeMillis())
                outboxDao.remove(item.messageId)
                AppLogger.info("Outbox", "sent attachment messageId=${message.messageId}")
            }
            is AppResult.Error -> backoff(item, result.error.message, createdAtUnixMs = message.createdAtUnixMs)
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
        val expired = createdAtUnixMs > 0 && System.currentTimeMillis() - createdAtUnixMs >= RETRY_WINDOW_MS
        if (expired) {
            messageDao.updateStatus(item.messageId, DeliveryStatus.FAILED)
            outboxDao.remove(item.messageId)
            AppLogger.warn("Outbox", "giving up messageId=${item.messageId} after retry window: $error")
            return
        }
        val backoffMs = (BASE_BACKOFF_MS shl minOf(attempt, MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
        outboxDao.update(
            item.copy(
                attemptCount = attempt,
                nextAttemptUnixMs = System.currentTimeMillis() + backoffMs,
                lastError = if (handedOff) MAILBOX_HANDOFF_ERROR else error,
            ),
        )
        AppLogger.info("Outbox", "retry messageId=${item.messageId} attempt=$attempt in ${backoffMs}ms: $error")
    }

    private fun buildEnvelope(message: MessageEntity, self: PeerIdentity): MessageEnvelope =
        MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8(message.messageId))
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(message.createdAtUnixMs)
            .setCounter(1)
            .setChat(ProtoChatMessage.newBuilder().setText(message.body.orEmpty()))
            .build()

    private fun MessageEntity.isAttachment(): Boolean = when (contentType) {
        ir.vmessenger.core.database.entity.MessageContentType.IMAGE,
        ir.vmessenger.core.database.entity.MessageContentType.VIDEO,
        ir.vmessenger.core.database.entity.MessageContentType.FILE,
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

        /** Outbox `lastError` shown while a sealed copy waits in the mailbox and direct retries continue. */
        const val MAILBOX_HANDOFF_ERROR = "در صندوق نگهداری شد"

        // Keep retrying an undelivered message this long (capped backoff) so it
        // arrives when a temporarily-offline peer returns, before giving up.
        private const val RETRY_WINDOW_MS = 24 * 60 * 60_000L
    }
}
