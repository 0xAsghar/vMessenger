package ir.vmessenger.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
     * Unlocks the developer tools (Debug and Logs screens) in a release build.
     * Toggled by seven taps on the version row in About; always false by default.
     */
    val developerModeEnabled: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_DEVELOPER_MODE] ?: DEFAULT_DEVELOPER_MODE }

    /** Whether opening a chat tells the sender it was read; on by default. */
    val sendReadReceipts: Flow<Boolean> = context.privacyDataStore.data
        .map { it[KEY_SEND_READ_RECEIPTS] ?: DEFAULT_SEND_READ_RECEIPTS }

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

    suspend fun setHideNotificationContent(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_HIDE_NOTIFICATIONS] = enabled }
    }

    companion object {
        const val DEFAULT_SEND_READ_RECEIPTS = true
        const val DEFAULT_DEVELOPER_MODE = false

        private val KEY_SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        private val KEY_HIDE_NOTIFICATIONS = booleanPreferencesKey("hide_notification_content")
        private val KEY_DEVELOPER_MODE = booleanPreferencesKey("developer_mode_enabled")
        private val KEY_SEND_READ_RECEIPTS = booleanPreferencesKey("send_read_receipts")
    }
}
