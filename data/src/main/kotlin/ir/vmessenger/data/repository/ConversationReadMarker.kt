package ir.vmessenger.data.repository

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.data.network.ReceiptSender
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The read-state half of [ConversationRepositoryImpl], kept apart from the
 * send/attachment machinery so it can be unit-tested against DAO fakes alone.
 *
 * Marking a conversation read means four things: the incoming messages become
 * READ, the unread badge resets, the conversation's notification is dismissed,
 * and — only when the user allows read receipts — the sender is told. The ids
 * are collected *before* the update so the receipt names exactly the messages
 * this call marked read; [ReceiptSender] coalesces them into one envelope.
 */
@Singleton
class ConversationReadMarker @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val receiptSender: ReceiptSender,
    private val readReceiptPolicy: ReadReceiptPolicy,
    private val notificationCanceller: ConversationNotificationCanceller,
) {
    suspend fun markRead(conversationId: String) {
        val unreadIds = messageDao.selectUnreadIncomingIds(conversationId)
        val now = System.currentTimeMillis()
        messageDao.markIncomingRead(conversationId, now)
        conversationDao.resetUnread(conversationId)
        notificationCanceller.cancel(conversationId)
        if (unreadIds.isEmpty()) return
        val contactId = conversationDao.getById(conversationId)?.contactId
        if (contactId == null || !readReceiptPolicy.readReceiptsEnabled()) return
        receiptSender.enqueueRead(contactId, unreadIds, now)
        AppLogger.info(TAG, "read receipt queued conversation=$conversationId count=${unreadIds.size}")
    }

    private companion object {
        const val TAG = "Messaging"
    }
}
