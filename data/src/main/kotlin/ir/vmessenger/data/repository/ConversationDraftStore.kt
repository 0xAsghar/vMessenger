package ir.vmessenger.data.repository

import ir.vmessenger.core.datastore.DraftPreferences
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where unsent composer text lives, behind an interface so the repository's
 * draft behaviour is testable without an Android `DataStore`.
 */
interface ConversationDraftStore {
    fun observe(conversationId: String): Flow<String>
    suspend fun save(conversationId: String, text: String)
    suspend fun clear(conversationId: String)
}

@Singleton
class DataStoreConversationDraftStore @Inject constructor(
    private val draftPreferences: DraftPreferences,
) : ConversationDraftStore {
    override fun observe(conversationId: String): Flow<String> = draftPreferences.draft(conversationId)

    override suspend fun save(conversationId: String, text: String) =
        draftPreferences.setDraft(conversationId, text)

    override suspend fun clear(conversationId: String) = draftPreferences.clearDraft(conversationId)
}
