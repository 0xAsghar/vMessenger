package ir.vmessenger.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** One contact's automatic-retry bookkeeping, in the form that survives a restart. */
data class ContactRetryRecord(val attempts: Int, val failures: Int, val nextAttemptUnixMs: Long)

private val Context.contactRetryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_contact_retry",
)

/**
 * How many automatic contact requests each contact has already been sent.
 *
 * Kept on disk because the cap is meaningless otherwise: an in-memory budget
 * hands a contact that stopped answering a year ago a fresh allowance on every
 * launch, and the app dials them hard all over again. The whole set is one entry
 * — it only ever holds the contacts still owed a request, a handful at most — so
 * a write is always a consistent snapshot.
 */
@Singleton
class ContactRetryPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun load(): Map<String, ContactRetryRecord> =
        context.contactRetryDataStore.data.first()[BUDGET_KEY]
            .orEmpty()
            .mapNotNull(::decode)
            .toMap()

    suspend fun save(records: Map<String, ContactRetryRecord>) {
        context.contactRetryDataStore.edit { preferences ->
            if (records.isEmpty()) {
                preferences.remove(BUDGET_KEY)
            } else {
                preferences[BUDGET_KEY] = records.map { (id, record) -> encode(id, record) }.toSet()
            }
        }
    }

    /** Forgets every budget (secure wipe). */
    suspend fun clear() {
        context.contactRetryDataStore.edit { it.clear() }
    }

    /** The contact id goes last so it may contain the separator; the counters never can. */
    private fun encode(contactId: String, record: ContactRetryRecord): String =
        listOf(record.attempts, record.failures, record.nextAttemptUnixMs, contactId).joinToString(SEPARATOR)

    /** Null for an entry written by a layout we no longer understand; it is simply dropped. */
    private fun decode(entry: String): Pair<String, ContactRetryRecord>? {
        val parts = entry.split(SEPARATOR, limit = FIELD_COUNT)
        val contactId = parts.getOrNull(3).orEmpty()
        val record = decodeRecord(parts)
        return if (contactId.isEmpty() || record == null) null else contactId to record
    }

    private fun decodeRecord(parts: List<String>): ContactRetryRecord? {
        val attempts = parts.getOrNull(0)?.toIntOrNull()
        val failures = parts.getOrNull(1)?.toIntOrNull()
        val next = parts.getOrNull(2)?.toLongOrNull()
        return if (attempts != null && failures != null && next != null) {
            ContactRetryRecord(attempts, failures, next)
        } else {
            null
        }
    }

    companion object {
        /** The on-disk layout, kept here so a test can assert it rather than guess it. */
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 4

        private val BUDGET_KEY = stringSetPreferencesKey("contact_request_retry_v1")
    }
}
