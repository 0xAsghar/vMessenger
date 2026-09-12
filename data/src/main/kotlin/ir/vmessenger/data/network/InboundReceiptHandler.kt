package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.MessageRecipientDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.proto.app.v1.Receipt
import ir.vmessenger.core.proto.app.v1.ReceiptType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies delivery/read receipts from an authenticated peer.
 *
 * A receipt only counts for a message we sent *to that peer*: the referenced
 * message must be OUTGOING and the sender must be one of its recipients,
 * otherwise anyone could flip ticks on other people's messages. The recipient
 * list is the authority here rather than the conversation's contact, because a
 * group message legitimately gets a receipt from each of its members.
 *
 * Statuses never move backwards and the receipt time is clamped to
 * `[createdAt, now]` so a peer cannot forge timestamps.
 */
@Singleton
@Suppress("LongParameterList") // one DAO per table a receipt touches, plus the aggregate
class InboundReceiptHandler @Inject constructor(
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val recipientDao: MessageRecipientDao,
    private val outboxDao: OutboxDao,
    private val deliveryAggregator: DeliveryAggregator,
) {
    suspend fun handle(contactId: String, receipt: Receipt) {
        val refs = buildList {
            add(receipt.refMessageId.toStringUtf8())
            receipt.refMessageIdsList.forEach { add(it.toStringUtf8()) }
        }.filter { it.isNotBlank() }.distinct()
        val senderKey = contactDao.getById(contactId)?.identityHash?.let(IdentityHashMatcher::routingKeyHex)
        if (senderKey == null) {
            AppLogger.warn(TAG, "receipt ignored: unknown contact=$contactId")
            return
        }
        val now = System.currentTimeMillis()
        for (refId in refs) {
            apply(contactId, senderKey, refId, receipt.type, receipt.atUnixMs, now)
        }
    }

    @Suppress("LongParameterList", "ReturnCount") // one parameter per field of the receipt being applied
    private suspend fun apply(
        contactId: String,
        senderKey: String,
        refId: String,
        type: ReceiptType,
        atUnixMs: Long,
        now: Long,
    ) {
        val message = messageDao.getById(refId)
        val recipient = recipientDao.forMessage(refId).firstOrNull { it.identityHash == senderKey }
        if (message == null || message.direction != MessageDirection.OUTGOING || recipient == null) {
            AppLogger.warn(TAG, "receipt ignored: ref=$refId was not sent to contact=$contactId")
            return
        }
        val status = type.toStatus() ?: return
        val ts = atUnixMs.coerceIn(message.createdAtUnixMs, maxOf(now, message.createdAtUnixMs))
        advance(refId, senderKey, status, ts)
        deliveryAggregator.recompute(refId)
        // Confirmed by this recipient — stop their receipt-wait re-sends promptly
        // instead of waiting for the outbox to notice on its next tick.
        outboxDao.remove(refId, senderKey)
        AppLogger.info(TAG, "receipt ${type.name} ref=$refId contact=$contactId")
    }

    private suspend fun advance(refId: String, senderKey: String, status: DeliveryStatus, ts: Long) {
        recipientDao.advance(
            messageId = refId,
            identityHash = senderKey,
            status = status,
            rank = status.rank(),
            sentAt = null,
            deliveredAt = ts.takeIf { status == DeliveryStatus.DELIVERED || status == DeliveryStatus.READ },
            readAt = ts.takeIf { status == DeliveryStatus.READ },
        )
    }

    private fun ReceiptType.toStatus(): DeliveryStatus? = when (this) {
        ReceiptType.RECEIPT_TYPE_DELIVERED -> DeliveryStatus.DELIVERED
        ReceiptType.RECEIPT_TYPE_READ -> DeliveryStatus.READ
        else -> null
    }

    private companion object {
        const val TAG = "Messaging"
    }
}
