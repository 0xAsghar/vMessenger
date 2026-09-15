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
        //
        // Gated on the *configured* flag as well as the state, and that is not belt and braces.
        // `LockState.Undetermined` is the starting value and only the activity's view model ever
        // resolves it, so in a process that never ran the UI — after a reboot, after the system
        // restarts the service — it stays undetermined forever. Read as "not Unlocked" on its own,
        // that hid the sender and the preview from every notification for users who have no app
        // lock at all, until they next opened the app.
        val locked = privacyPreferences.appLockEnabled.first() && appLock.state.value != LockState.Unlocked
        val hideContent = privacyPreferences.hideNotificationContent.first() || locked
        messageNotificationManager.showMessageNotification(
            senderName = senderName,
            preview = preview,
            conversationId = conversationId,
            hideContent = hideContent,
        )
    }
}
