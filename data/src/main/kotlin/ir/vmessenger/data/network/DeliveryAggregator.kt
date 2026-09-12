package ir.vmessenger.data.network

import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.MessageRecipientDao
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageRecipientEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collapses the per-recipient delivery rows of a message into the single status
 * its bubble renders.
 *
 * A group message is delivered pairwise, so "delivered" can only mean *everyone*
 * has it — showing two ticks while one member is still offline would be a lie.
 * A 1:1 message has exactly one recipient row, so the same rules produce exactly
 * the old behaviour.
 */
@Singleton
class DeliveryAggregator @Inject constructor(
    private val messageDao: MessageDao,
    private val recipientDao: MessageRecipientDao,
) {
    /**
     * Recomputes and stores the message's status. A message with no recipient rows
     * is left alone: it predates the fan-out or was never queued, and guessing a
     * status for it would only overwrite what the sender path already set.
     */
    suspend fun recompute(messageId: String) {
        val rows = recipientDao.forMessage(messageId).ifEmpty { return }
        when (aggregate(rows)) {
            DeliveryStatus.READ -> messageDao.markRead(messageId, DeliveryStatus.READ, rows.latest { it.readAtUnixMs })
            DeliveryStatus.DELIVERED ->
                messageDao.markDelivered(messageId, DeliveryStatus.DELIVERED, rows.latest { it.deliveredAtUnixMs })
            DeliveryStatus.SENT ->
                messageDao.markSent(messageId, DeliveryStatus.SENT, rows.earliest { it.sentAtUnixMs })
            DeliveryStatus.FAILED -> messageDao.updateStatus(messageId, DeliveryStatus.FAILED)
            DeliveryStatus.QUEUED -> messageDao.updateStatus(messageId, DeliveryStatus.QUEUED)
        }
    }

    private fun List<MessageRecipientEntity>.latest(field: (MessageRecipientEntity) -> Long?): Long =
        mapNotNull(field).maxOrNull() ?: System.currentTimeMillis()

    private fun List<MessageRecipientEntity>.earliest(field: (MessageRecipientEntity) -> Long?): Long =
        mapNotNull(field).minOrNull() ?: System.currentTimeMillis()

    private companion object {
        /**
         * Everyone read it → READ; everyone at least received it → DELIVERED;
         * everyone failed → FAILED; anyone has it on the wire → SENT; otherwise
         * still QUEUED.
         */
        fun aggregate(rows: List<MessageRecipientEntity>): DeliveryStatus {
            val statuses = rows.map { it.status }
            return when {
                statuses.all { it == DeliveryStatus.READ } -> DeliveryStatus.READ
                statuses.all { it == DeliveryStatus.DELIVERED || it == DeliveryStatus.READ } ->
                    DeliveryStatus.DELIVERED
                statuses.all { it == DeliveryStatus.FAILED } -> DeliveryStatus.FAILED
                statuses.any { it != DeliveryStatus.QUEUED && it != DeliveryStatus.FAILED } -> DeliveryStatus.SENT
                else -> DeliveryStatus.QUEUED
            }
        }
    }
}

/**
 * Order of [DeliveryStatus] as a delivery progresses, so a recipient row can only
 * be moved forward. FAILED sits just above QUEUED: a failed attempt is still
 * retried, and a later success must be able to overwrite it.
 */
internal fun DeliveryStatus.rank(): Int = when (this) {
    DeliveryStatus.QUEUED -> 0
    DeliveryStatus.FAILED -> 1
    DeliveryStatus.SENT -> 2
    DeliveryStatus.DELIVERED -> 3
    DeliveryStatus.READ -> 4
}
