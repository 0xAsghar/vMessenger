package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.Receipt
import ir.vmessenger.core.proto.app.v1.ReceiptType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies delivery/read receipts from an authenticated peer.
 *
 * A receipt only counts for a message we sent *to that peer*: the referenced
 * message must be OUTGOING and live in the conversation of the sending
 * contact, otherwise anyone could flip ticks on other people's messages.
 * Statuses never move backwards and the receipt time is clamped to
 * `[createdAt, now]` so a peer cannot forge timestamps.
 */
@Singleton
class InboundReceiptHandler @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val outboxDao: OutboxDao,
) {
    suspend fun handle(contactId: String, receipt: Receipt) {
        val refs = buildList {
            add(receipt.refMessageId.toStringUtf8())
            receipt.refMessageIdsList.forEach { add(it.toStringUtf8()) }
        }.filter { it.isNotBlank() }.distinct()
        val now = System.currentTimeMillis()
        for (refId in refs) {
            apply(contactId, refId, receipt.type, receipt.atUnixMs, now)
        }
    }

    private suspend fun apply(contactId: String, refId: String, type: ReceiptType, atUnixMs: Long, now: Long) {
        val message = messageDao.getById(refId)
        val owner = message?.let { conversationDao.getById(it.conversationId)?.contactId }
        if (message == null || message.direction != MessageDirection.OUTGOING || owner != contactId) {
            AppLogger.warn("Messaging", "receipt ignored: ref=$refId not an outgoing message of contact=$contactId")
            return
        }
        val ts = atUnixMs.coerceIn(message.createdAtUnixMs, maxOf(now, message.createdAtUnixMs))
        val transitioned = transition(message, type, ts)
        val settled = message.status == DeliveryStatus.DELIVERED || message.status == DeliveryStatus.READ
        if (transitioned || settled) {
            // Confirmed by the recipient — stop the receipt-wait re-sends promptly
            // instead of waiting for the outbox to notice on its next tick.
            outboxDao.remove(refId)
        }
        if (transitioned) {
            AppLogger.info("Messaging", "receipt ${type.name} ref=$refId contact=$contactId")
        }
    }

    /** Applies the status change when it moves forward; DELIVERED needs QUEUED/SENT, READ needs SENT/DELIVERED. */
    private suspend fun transition(message: MessageEntity, type: ReceiptType, ts: Long): Boolean = when (type) {
        ReceiptType.RECEIPT_TYPE_DELIVERED ->
            if (message.status == DeliveryStatus.QUEUED || message.status == DeliveryStatus.SENT) {
                messageDao.markDelivered(message.messageId, DeliveryStatus.DELIVERED, ts)
                true
            } else {
                false
            }
        ReceiptType.RECEIPT_TYPE_READ ->
            if (message.status == DeliveryStatus.SENT || message.status == DeliveryStatus.DELIVERED) {
                messageDao.markRead(message.messageId, DeliveryStatus.READ, ts)
                true
            } else {
                false
            }
        else -> false
    }
}
