package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun getOrCreateConversation(contactId: String): String
    suspend fun sendMessage(conversationId: String, text: String): AppResult<String>

    /**
     * Queues a photo/video/file for delivery. [sourceUri] is a content Uri from
     * the system picker; the file is copied into app-private storage first.
     */
    suspend fun sendAttachment(conversationId: String, sourceUri: String): AppResult<String>
    suspend fun markConversationRead(conversationId: String)
}
