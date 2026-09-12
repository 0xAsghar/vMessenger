package ir.vmessenger.data.repository

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.UnreadIncoming
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
@Suppress("LongParameterList") // one DAO per table read, plus the receipt, policy and notification ports
class ConversationReadMarker @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val receiptSender: ReceiptSender,
    private val readReceiptPolicy: ReadReceiptPolicy,
    private val notificationCanceller: ConversationNotificationCanceller,
) {
    suspend fun markRead(conversationId: String) {
        val unread = messageDao.selectUnreadIncoming(conversationId)
        val now = System.currentTimeMillis()
        messageDao.markIncomingRead(conversationId, now)
        conversationDao.resetUnread(conversationId)
        notificationCanceller.cancel(conversationId)
        if (unread.isEmpty() || !readReceiptPolicy.readReceiptsEnabled()) return
        val conversation = conversationDao.getById(conversationId) ?: return
        val contactId = conversation.contactId
        if (contactId != null) {
            receiptSender.enqueueRead(contactId, unread.map { it.messageId }, now)
        } else {
            sendGroupReceipts(unread, now)
        }
        AppLogger.info(TAG, "read receipt queued conversation=$conversationId count=${unread.size}")
    }

    /**
     * A group message is acknowledged to whoever sent it, not to the conversation:
     * each sender only ever sees receipts for their own messages, which is also
     * what stops one member's read state leaking to the rest.
     */
    private suspend fun sendGroupReceipts(unread: List<UnreadIncoming>, now: Long) {
        unread.groupBy { it.senderIdentityHash }.forEach { (senderKey, messages) ->
            val contactId = senderKey?.let { contactDao.getByRoutingKey(it) }?.id ?: return@forEach
            receiptSender.enqueueRead(contactId, messages.map { it.messageId }, now)
        }
    }

    private companion object {
        const val TAG = "Messaging"
    }
}
