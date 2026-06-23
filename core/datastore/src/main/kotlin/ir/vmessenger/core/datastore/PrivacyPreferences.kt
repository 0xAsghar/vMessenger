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

    suspend fun setScreenSecurityEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_SCREEN_SECURITY] = enabled }
    }

    suspend fun setHideNotificationContent(enabled: Boolean) {
        context.privacyDataStore.edit { it[KEY_HIDE_NOTIFICATIONS] = enabled }
    }

    companion object {
        private val KEY_SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        private val KEY_HIDE_NOTIFICATIONS = booleanPreferencesKey("hide_notification_content")
    }
}
