package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.common.network.NetworkPath
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
class OutboxDispatcher @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val outboxDao: OutboxDao,
    private val identityRepository: IdentityRepository,
    private val messagingService: MessagingService,
    private val mailboxService: MailboxService,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

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

    private suspend fun drainOnce() {
        val now = System.currentTimeMillis()
        val due = outboxDao.due(now)
        if (due.isEmpty()) return
        val identity = identityRepository.getIdentity() ?: return
        val self = selfPeer(identity)
        for (item in due) {
            processItem(item, identity, self)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun processItem(item: OutboxEntity, identity: Identity, self: PeerIdentity) {
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
        val peer = PeerIdentity(
            identityHash = contact.identityHash,
            ed25519PublicKey = contact.ed25519Public,
            x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(X25519_KEY_SIZE),
        )
        val envelope = buildEnvelope(message, identity)
        when (val result = messagingService.send(conversation.contactId, self, peer, envelope)) {
            is AppResult.Success -> {
                messageDao.markSent(message.messageId, DeliveryStatus.SENT, System.currentTimeMillis())
                outboxDao.remove(item.messageId)
                AppLogger.info("Outbox", "sent messageId=${message.messageId} attempt=${item.attemptCount + 1}")
            }
            is AppResult.Error -> backoff(item, result.error.message, peer, envelope)
        }
    }

    private suspend fun backoff(
        item: OutboxEntity,
        error: String?,
        peer: PeerIdentity? = null,
        envelope: MessageEnvelope? = null,
    ) {
        val attempt = item.attemptCount + 1
        if (attempt >= MAX_ATTEMPTS) {
            if (P2PConfig.storeAndForwardEnabled && peer != null && envelope != null) {
                mailboxService.enqueueForRecipient(
                    recipientHash = peer.identityHash,
                    sealedPayload = envelope.toByteArray(),
                )
                NetworkPathTracker.record(
                    path = NetworkPath.STORE_AND_FORWARD,
                    detail = "outbox-${item.messageId}",
                )
                messageDao.markSent(item.messageId, DeliveryStatus.SENT, System.currentTimeMillis())
                outboxDao.remove(item.messageId)
                AppLogger.info("Outbox", "queued to mailbox messageId=${item.messageId}")
                return
            }
            messageDao.updateStatus(item.messageId, DeliveryStatus.FAILED)
            outboxDao.remove(item.messageId)
            AppLogger.warn("Outbox", "giving up messageId=${item.messageId} after $attempt attempts: $error")
            return
        }
        val backoffMs = (BASE_BACKOFF_MS shl minOf(attempt, MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
        outboxDao.update(
            item.copy(
                attemptCount = attempt,
                nextAttemptUnixMs = System.currentTimeMillis() + backoffMs,
                lastError = error,
            ),
        )
        AppLogger.info("Outbox", "retry messageId=${item.messageId} attempt=$attempt in ${backoffMs}ms: $error")
    }

    private fun buildEnvelope(message: MessageEntity, identity: Identity): MessageEnvelope =
        MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8(message.messageId))
            .setSenderIdentityHash(ByteString.copyFrom(identity.identityHash))
            .setSentAtUnixMs(message.createdAtUnixMs)
            .setCounter(1)
            .setChat(ProtoChatMessage.newBuilder().setText(message.body.orEmpty()))
            .build()

    private suspend fun selfPeer(identity: Identity): PeerIdentity = PeerIdentity(
        identityHash = identity.identityHash,
        ed25519PublicKey = identity.ed25519PublicKey,
        x25519StaticPublicKey = identity.x25519StaticPublicKey,
        ed25519PrivateKey = identityRepository.getEd25519PrivateKey(),
        x25519StaticPrivateKey = identityRepository.getX25519StaticPrivateKey(),
    )

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_SHIFT = 5
        private const val MAX_ATTEMPTS = 12
        private const val X25519_KEY_SIZE = 32
    }
}
