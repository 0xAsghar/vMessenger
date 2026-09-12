package ir.vmessenger.data.repository

import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.notifications.MessageNotificationManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Whether READ receipts may leave the device (the "رسید خواندن" privacy toggle). */
interface ReadReceiptPolicy {
    suspend fun readReceiptsEnabled(): Boolean
}

@Singleton
class PreferenceReadReceiptPolicy @Inject constructor(
    private val privacyPreferences: PrivacyPreferences,
) : ReadReceiptPolicy {
    override suspend fun readReceiptsEnabled(): Boolean = privacyPreferences.sendReadReceipts.first()
}

/** Dismisses a conversation's "new message" notification once it has been read. */
interface ConversationNotificationCanceller {
    fun cancel(conversationId: String)
}

@Singleton
class AndroidConversationNotificationCanceller @Inject constructor(
    private val messageNotificationManager: MessageNotificationManager,
) : ConversationNotificationCanceller {
    override fun cancel(conversationId: String) = messageNotificationManager.cancel(conversationId)
}
