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
    suspend fun markConversationRead(conversationId: String)
}
