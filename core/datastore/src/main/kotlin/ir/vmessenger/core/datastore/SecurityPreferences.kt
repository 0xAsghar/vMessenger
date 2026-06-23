package ir.vmessenger.core.datastore

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_security",
)

private val WRAPPED_DB_PASSPHRASE_KEY = stringPreferencesKey("wrapped_db_passphrase")

@Singleton
class SecurityPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun getWrappedDbPassphrase(): ByteArray? {
        val encoded = context.securityDataStore.data.first()[WRAPPED_DB_PASSPHRASE_KEY] ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    suspend fun setWrappedDbPassphrase(wrapped: ByteArray) {
        context.securityDataStore.edit { preferences ->
            preferences[WRAPPED_DB_PASSPHRASE_KEY] = Base64.encodeToString(wrapped, Base64.NO_WRAP)
        }
    }

    suspend fun clear() {
        context.securityDataStore.edit { it.clear() }
    }
}
