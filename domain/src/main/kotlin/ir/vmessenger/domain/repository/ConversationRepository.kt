package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.Conversation
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun getOrCreateConversation(contactId: String): String
    suspend fun sendMessage(conversationId: String, text: String): AppResult<String>

    /**
     * Queues a photo/video/file for delivery. [sourceUri] is a content Uri from
     * the system picker; the file is copied (encrypted) into app-private storage first.
     */
    suspend fun sendAttachment(conversationId: String, sourceUri: String): AppResult<String>
    suspend fun markConversationRead(conversationId: String)

    /** Live progress of the attachment transfers of this conversation, keyed by message id. */
    fun observeAttachmentProgress(conversationId: String): Flow<Map<String, AttachmentProgress>>

    /** Plaintext stream of a stored attachment (thumbnails, in-app viewers); null when unavailable. */
    suspend fun openAttachment(messageId: String): InputStream?

    /**
     * Decrypts the attachment of [messageId] into the short-lived view cache and
     * returns the plaintext file path for `ACTION_VIEW` through the FileProvider.
     */
    suspend fun exportAttachmentForViewing(messageId: String): AppResult<String>
}
