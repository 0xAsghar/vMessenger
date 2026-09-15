package ir.vmessenger.data.network

import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.notifications.MessageNotificationManager
import ir.vmessenger.data.lock.AppLockCoordinator
import ir.vmessenger.data.lock.LockState
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
    private val appLock: AppLockCoordinator,
) : IncomingMessageNotifier {
    override suspend fun notify(senderName: String, preview: String, conversationId: String) {
        // The lock forces this on, the same way it forces FLAG_SECURE regardless of the screen
        // security setting. Someone who put a PIN in front of their messages did not mean "unless
        // they arrive while you are looking at the phone", and the shade is the one surface the
        // lock screen does not cover. The user's own preference still applies when unlocked.
        val hideContent = privacyPreferences.hideNotificationContent.first() ||
            appLock.state.value != LockState.Unlocked
        messageNotificationManager.showMessageNotification(
            senderName = senderName,
            preview = preview,
            conversationId = conversationId,
            hideContent = hideContent,
        )
    }
}
