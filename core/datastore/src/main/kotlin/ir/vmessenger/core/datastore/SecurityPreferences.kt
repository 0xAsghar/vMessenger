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

internal val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_security",
)

private val WRAPPED_DB_PASSPHRASE_KEY = stringPreferencesKey("wrapped_db_passphrase")
private val WRAPPED_ATTACHMENT_KEY = stringPreferencesKey("wrapped_attachment_key")
private val DHT_NODE_ID_KEY = stringPreferencesKey("dht_node_id")

@Singleton
class SecurityPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dhtNodeIdStorage = object : DhtNodeIdStorage {
        override suspend fun read(): String? = context.securityDataStore.data.first()[DHT_NODE_ID_KEY]

        override suspend fun write(encoded: String) {
            context.securityDataStore.edit { it[DHT_NODE_ID_KEY] = encoded }
        }
    }

    /** Per-device random 32-byte DHT node id, generated once and persisted (see [DhtNodeId]). */
    suspend fun getOrCreateDhtNodeId(): ByteArray = DhtNodeId.getOrCreate(dhtNodeIdStorage)

    suspend fun getWrappedDbPassphrase(): ByteArray? = getWrapped(WRAPPED_DB_PASSPHRASE_KEY)

    suspend fun setWrappedDbPassphrase(wrapped: ByteArray) = setWrapped(WRAPPED_DB_PASSPHRASE_KEY, wrapped)

    /** Removes the ordinary copy, leaving the app lock's auth-bound one as the only way in. */
    suspend fun clearWrappedDbPassphrase() {
        context.securityDataStore.edit { it.remove(WRAPPED_DB_PASSPHRASE_KEY) }
    }

    /** Keystore-wrapped 32-byte master key for attachments at rest (never the DB passphrase). */
    suspend fun getWrappedAttachmentKey(): ByteArray? = getWrapped(WRAPPED_ATTACHMENT_KEY)

    suspend fun setWrappedAttachmentKey(wrapped: ByteArray) = setWrapped(WRAPPED_ATTACHMENT_KEY, wrapped)

    private suspend fun getWrapped(key: Preferences.Key<String>): ByteArray? {
        val encoded = context.securityDataStore.data.first()[key] ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    private suspend fun setWrapped(key: Preferences.Key<String>, wrapped: ByteArray) {
        context.securityDataStore.edit { preferences -> preferences[key] = encode(wrapped) }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    suspend fun clear() {
        context.securityDataStore.edit { it.clear() }
    }
}
