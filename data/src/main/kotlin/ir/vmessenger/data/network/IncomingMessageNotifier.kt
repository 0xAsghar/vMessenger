package ir.vmessenger.data.network

import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.notifications.MessageNotificationManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Raises the "new message" notification; the collector decides *whether* to raise it. */
interface IncomingMessageNotifier {
    suspend fun notify(senderName: String, preview: String, conversationId: String)
}

@Singleton
class DefaultIncomingMessageNotifier @Inject constructor(
    private val messageNotificationManager: MessageNotificationManager,
    private val privacyPreferences: PrivacyPreferences,
) : IncomingMessageNotifier {
    override suspend fun notify(senderName: String, preview: String, conversationId: String) {
        val hideContent = privacyPreferences.hideNotificationContent.first()
        messageNotificationManager.showMessageNotification(
            senderName = senderName,
            preview = preview,
            conversationId = conversationId,
            hideContent = hideContent,
        )
    }
}
