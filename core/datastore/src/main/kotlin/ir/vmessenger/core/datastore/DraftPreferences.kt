package ir.vmessenger.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.draftDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_drafts",
)

/**
 * Unsent composer text, one entry per conversation (`draft_<conversationId>`).
 *
 * A draft is plaintext the user typed but never sent, so it is deleted as soon
 * as it is sent and whenever the conversation itself goes away — see
 * `ConversationRepository.deleteConversation`.
 */
@Singleton
class DraftPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun draft(conversationId: String): Flow<String> = context.draftDataStore.data
        .map { it[keyFor(conversationId)].orEmpty() }

    /** Blank text removes the entry instead of storing an empty string. */
    suspend fun setDraft(conversationId: String, text: String) {
        context.draftDataStore.edit { preferences ->
            val key = keyFor(conversationId)
            if (text.isBlank()) preferences.remove(key) else preferences[key] = text
        }
    }

    suspend fun clearDraft(conversationId: String) {
        context.draftDataStore.edit { it.remove(keyFor(conversationId)) }
    }

    /** Drops every draft (secure wipe). */
    suspend fun clear() {
        context.draftDataStore.edit { it.clear() }
    }

    companion object {
        /** The key layout is part of the on-disk format; kept here so it can be asserted in tests. */
        fun keyName(conversationId: String): String = "draft_$conversationId"

        private fun keyFor(conversationId: String) = stringPreferencesKey(keyName(conversationId))
    }
}
