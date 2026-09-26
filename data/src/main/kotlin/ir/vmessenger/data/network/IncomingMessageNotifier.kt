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

    /** A verified contact asking us to share our location; raises the prompt, changes nothing. */
    suspend fun notifyLocationRequest(senderName: String, conversationId: String)

    /** Someone asking to become a contact; opening it shows the request to approve or reject. */
    suspend fun notifyContactRequest(requesterName: String, requestId: String)
}

@Singleton
class DefaultIncomingMessageNotifier @Inject constructor(
    private val messageNotificationManager: MessageNotificationManager,
    private val privacyPreferences: PrivacyPreferences,
    private val appLock: AppLockCoordinator,
) : IncomingMessageNotifier {
    override suspend fun notify(senderName: String, preview: String, conversationId: String) {
        messageNotificationManager.showMessageNotification(
            senderName = senderName,
            preview = preview,
            conversationId = conversationId,
            hideContent = hideContent(),
        )
    }

    override suspend fun notifyLocationRequest(senderName: String, conversationId: String) {
        messageNotificationManager.showLocationRequest(
            senderName = senderName,
            conversationId = conversationId,
            hideContent = hideContent(),
        )
    }

    override suspend fun notifyContactRequest(requesterName: String, requestId: String) {
        messageNotificationManager.showContactRequest(
            requesterName = requesterName,
            requestId = requestId,
            hideContent = hideContent(),
        )
    }

    /**
     * The lock forces content hidden, the same way it forces `FLAG_SECURE` regardless of the screen
     * security setting. Someone who put a PIN in front of their messages did not mean "unless they
     * arrive while you are looking at the phone", and the shade is the one surface the lock screen
     * does not cover. The user's own preference still applies when unlocked.
     *
     * Gated on the *configured* flag as well as the state, and that is not belt and braces.
     * [LockState.Undetermined] is the starting value and only the activity's view model ever
     * resolves it, so in a process that never ran the UI — after a reboot, after the system restarts
     * the service — it stays undetermined forever. Read as "not Unlocked" on its own, that hid the
     * sender and the preview from every notification for users who have no app lock at all, until
     * they next opened the app.
     */
    private suspend fun hideContent(): Boolean {
        val locked = privacyPreferences.appLockEnabled.first() && appLock.state.value != LockState.Unlocked
        return privacyPreferences.hideNotificationContent.first() || locked
    }
}
