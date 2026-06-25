package ir.vmessenger.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.discoveryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_discovery",
)

@Singleton
class DiscoveryPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun nextPublishSequence(identityHash: ByteArray): Long {
        val key = sequenceKey(identityHash)
        var next = 0L
        context.discoveryDataStore.edit { preferences ->
            val current = preferences[key] ?: 0L
            next = current + 1
            preferences[key] = next
        }
        return next
    }

    suspend fun bumpPublishSequence(identityHash: ByteArray, atLeast: Long): Long {
        val key = sequenceKey(identityHash)
        var next = atLeast
        context.discoveryDataStore.edit { preferences ->
            val current = preferences[key] ?: 0L
            next = maxOf(current, atLeast)
            preferences[key] = next
        }
        return next
    }

    private fun sequenceKey(identityHash: ByteArray): Preferences.Key<Long> =
        longPreferencesKey("publish_seq_${identityHash.toHexKey()}")

    private fun ByteArray.toHexKey(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
