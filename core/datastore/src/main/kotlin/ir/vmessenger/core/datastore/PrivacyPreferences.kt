package ir.vmessenger.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.privacyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_privacy",
)

@Singleton
class PrivacyPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val screenSecurityEnabled: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_SCREEN_SECURITY] ?: true }

    val hideNotificationContent: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_HIDE_NOTIFICATIONS] ?: false }

    /**
     * Whether the user has been told *why* this app needs notification permission.
     *
     * Asked once. The permission is load-bearing here — the foreground-service notice is what keeps
     * the connection alive — so it is explained before the system dialog rather than sprung; and
     * once explained it is never raised again, because Android stops showing the dialog after two
     * refusals and nagging would only train the user to dismiss it.
     */
    val notificationRationaleShown: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_NOTIFICATION_RATIONALE] ?: false }

    /**
     * Unlocks the developer tools (Debug and Logs screens) in a release build.
     * Toggled by seven taps on the version row in About; always false by default.
     */
    val developerModeEnabled: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_DEVELOPER_MODE] ?: DEFAULT_DEVELOPER_MODE }

    /** Whether opening a chat tells the sender it was read; on by default. */
    val sendReadReceipts: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_SEND_READ_RECEIPTS] ?: DEFAULT_SEND_READ_RECEIPTS }

    /** Whether the app asks for a PIN or a biometric before showing anything. */
    val appLockEnabled: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_APP_LOCK] ?: DEFAULT_APP_LOCK }

    /**
     * Whether the lock also holds the database key.
     *
     * Off, the lock covers the screen and messages keep arriving. On, the passphrase is re-wrapped
     * under a key the hardware will not use until the user authenticates — real at-rest protection,
     * and delivery stops while locked. The settings copy says exactly that.
     */
    val strictLockEnabled: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_STRICT_LOCK] ?: DEFAULT_STRICT_LOCK }

    /** Minutes in the background before the lock re-arms; zero means immediately. */
    val autoLockMinutes: Flow<Int> = context.privacyDataStore.data
        .map { it[KEY_AUTO_LOCK_MINUTES] ?: DEFAULT_AUTO_LOCK_MINUTES }

    /**
     * Whether repeated wrong PINs erase the app. Off by default and never otherwise: it is an
     * irreversible destruction trigger reachable by anyone holding the phone, including a child.
     */
    val wipeOnFailedAttempts: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_WIPE_ON_FAILURES] ?: DEFAULT_WIPE_ON_FAILURES }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_APP_LOCK] = enabled }
    }

    suspend fun setStrictLockEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_STRICT_LOCK] = enabled }
    }

    suspend fun setAutoLockMinutes(minutes: Int) {
        context.privacyDataStore.edit { it[KEY_AUTO_LOCK_MINUTES] = minutes }
    }

    suspend fun setWipeOnFailedAttempts(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_WIPE_ON_FAILURES] = enabled }
    }

    suspend fun setSendReadReceipts(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_SEND_READ_RECEIPTS] = enabled }
    }

    /** Drops every privacy preference back to its default (secure wipe). */
    suspend fun clear() {
        context.privacyDataStore.edit { it.clear() }
    }

    suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_DEVELOPER_MODE] = enabled }
    }

    suspend fun setScreenSecurityEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_SCREEN_SECURITY] = enabled }
    }

    suspend fun setNotificationRationaleShown(shown: Boolean) {
        context.privacyDataStore.edit { it[KEY_NOTIFICATION_RATIONALE] = shown }
    }

    suspend fun setHideNotificationContent(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_HIDE_NOTIFICATIONS] = enabled }
    }

    companion object {
        const val DEFAULT_SEND_READ_RECEIPTS = true
        const val DEFAULT_DEVELOPER_MODE = false
        const val DEFAULT_APP_LOCK = false
        const val DEFAULT_STRICT_LOCK = false
        const val DEFAULT_AUTO_LOCK_MINUTES = 1
        const val DEFAULT_WIPE_ON_FAILURES = false

        private val KEY_SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        private val KEY_HIDE_NOTIFICATIONS = booleanPreferencesKey("hide_notification_content")
        private val KEY_NOTIFICATION_RATIONALE = booleanPreferencesKey("notification_rationale_shown")
        private val KEY_DEVELOPER_MODE = booleanPreferencesKey("developer_mode_enabled")
        private val KEY_SEND_READ_RECEIPTS = booleanPreferencesKey("send_read_receipts")
        private val KEY_APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        private val KEY_STRICT_LOCK = booleanPreferencesKey("app_lock_strict")
        private val KEY_AUTO_LOCK_MINUTES = intPreferencesKey("app_lock_auto_minutes")
        private val KEY_WIPE_ON_FAILURES = booleanPreferencesKey("app_lock_wipe_on_failures")
    }
}
