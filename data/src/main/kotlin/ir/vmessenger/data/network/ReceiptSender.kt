package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.Receipt
import ir.vmessenger.core.proto.app.v1.ReceiptType
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends delivery/read receipts off the inbound path.
 *
 * Handlers only enqueue; one drain coroutine delivers. For each receipt the
 * cheapest route wins: the inbound session the message arrived on (same
 * connection, no dial), else an already-open outbound session, else a full
 * resolve+dial capped at [SEND_TIMEOUT_MS]. Failures are dropped: the sender
 * re-sends until it sees a receipt and we re-ack duplicates, so a lost receipt
 * heals on its own. READ receipts queued for the same contact are coalesced
 * into one envelope (`ref_message_ids`).
 */
@Singleton
class ReceiptSender @Inject constructor(
    private val messaging: MessagingPort,
    private val selfIdentityCache: SelfIdentityCache,
    private val contactDao: ContactDao,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    /** One queued receipt; [session] is the inbound session it should be answered on, when still open. */
    class Request(
        val contactId: String,
        val refMessageIds: List<String>,
        val type: ReceiptType,
        val atUnixMs: Long,
        val session: ActiveSecureSession?,
    ) {
        /** First ref goes in `ref_message_id`, the rest in `ref_message_ids` (batched READ). */
        fun toEnvelope(self: PeerIdentity): MessageEnvelope {
            val first = refMessageIds.first()
            val rest = refMessageIds.drop(1)
            val receipt = Receipt.newBuilder()
                .setRefMessageId(ByteString.copyFromUtf8(first))
                .setType(type)
                .setAtUnixMs(atUnixMs)
                .addAllRefMessageIds(rest.map { ByteString.copyFromUtf8(it) })
            val messageId = if (rest.isEmpty()) "receipt-$first" else "receipt-$first-${refMessageIds.size}"
            return MessageEnvelope.newBuilder()
                .setMessageId(ByteString.copyFromUtf8(messageId))
                .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
                .setSentAtUnixMs(atUnixMs)
                .setCounter(1)
                .setReceipt(receipt)
                .build()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler(TAG))
    private val queue = Channel<Request>(QUEUE_CAPACITY)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { drain() }
    }

    /** Stops draining; queued receipts stay queued and go out after the next [start]. */
    fun stop() {
        started = false
        scope.coroutineContext.cancelChildren()
    }

    /** Queues a DELIVERED receipt for [messageId]; false when the queue is full (the receipt is dropped). */
    fun enqueueDelivered(
        contactId: String,
        messageId: String,
        atUnixMs: Long,
        session: ActiveSecureSession?,
    ): Boolean = enqueue(Request(contactId, listOf(messageId), ReceiptType.RECEIPT_TYPE_DELIVERED, atUnixMs, session))

    /** Queues one READ receipt covering every id in [messageIds]. */
    fun enqueueRead(
        contactId: String,
        messageIds: List<String>,
        atUnixMs: Long,
        session: ActiveSecureSession? = null,
    ): Boolean {
        val ids = messageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return false
        return enqueue(Request(contactId, ids, ReceiptType.RECEIPT_TYPE_READ, atUnixMs, session))
    }

    private fun enqueue(request: Request): Boolean {
        val queued = queue.trySend(request).isSuccess
        if (!queued) {
            AppLogger.warn(TAG, "receipt queue full; dropped ${request.type.name} for contact=${request.contactId}")
        }
        return queued
    }

    private suspend fun drain() {
        for (request in queue) {
            val merged = if (request.type == ReceiptType.RECEIPT_TYPE_READ) coalesceRead(request) else request
            runCatching { deliver(merged) }
                .onFailure { AppLogger.warn(TAG, "receipt send failed contact=${merged.contactId}: ${it.message}") }
        }
    }

    /** Folds every READ receipt already queued for the same contact into [first]. */
    private fun coalesceRead(first: Request): Request {
        val ids = LinkedHashSet(first.refMessageIds)
        var session = first.session
        var atUnixMs = first.atUnixMs
        val deferred = ArrayList<Request>()
        while (ids.size < MAX_BATCH_REFS) {
            val next = queue.tryReceive().getOrNull() ?: break
            if (next.type == ReceiptType.RECEIPT_TYPE_READ && next.contactId == first.contactId) {
                ids += next.refMessageIds
                if (session == null) session = next.session
                atUnixMs = maxOf(atUnixMs, next.atUnixMs)
            } else {
                deferred += next
            }
        }
        // Anything else we pulled goes back in order; the queue never lost it.
        deferred.forEach { queue.trySend(it) }
        return Request(first.contactId, ids.toList(), first.type, atUnixMs, session)
    }

    private suspend fun deliver(request: Request) {
        val self = selfIdentityCache.get() ?: return
        val envelope = request.toEnvelope(self)
        val route = when {
            writeOnInbound(request.session, envelope) -> "inbound session"
            messaging.sendOnExistingSession(request.contactId, envelope) -> "outbound session"
            dial(request.contactId, self, envelope) -> "new session"
            else -> null
        }
        if (route != null) AppLogger.info(TAG, "receipt sent on $route contact=${request.contactId}")
    }

    /** Bounded like the other routes: a peer that stopped reading must not stall every contact's receipts. */
    private suspend fun writeOnInbound(session: ActiveSecureSession?, envelope: MessageEnvelope): Boolean {
        val inbound = session?.takeIf { !it.isClosed } ?: return false
        return runCatching { withTimeout(SEND_TIMEOUT_MS) { inbound.writeSealed(envelope) } }.isSuccess
    }

    /** Last resort: resolve and dial, but never longer than [SEND_TIMEOUT_MS]; a failure is logged and dropped. */
    private suspend fun dial(contactId: String, self: PeerIdentity, envelope: MessageEnvelope): Boolean {
        val contact = contactDao.getById(contactId) ?: return false
        val result = withTimeoutOrNull(SEND_TIMEOUT_MS) { messaging.send(contactId, self, contact.toPeer(), envelope) }
        if (result !is AppResult.Success) {
            val reason = (result as? AppResult.Error)?.error?.message ?: "timeout"
            AppLogger.info(TAG, "receipt dropped contact=$contactId: $reason")
        }
        return result is AppResult.Success
    }

    companion object {
        private const val TAG = "Receipt"
        const val QUEUE_CAPACITY = 1024
        const val SEND_TIMEOUT_MS = 10_000L
        const val MAX_BATCH_REFS = 64
    }
}

private const val X25519_KEY_SIZE = 32

private fun ContactEntity.toPeer() = PeerIdentity(
    identityHash = identityHash,
    ed25519PublicKey = ed25519Public,
    x25519StaticPublicKey = x25519StaticPublic ?: ByteArray(X25519_KEY_SIZE),
)
