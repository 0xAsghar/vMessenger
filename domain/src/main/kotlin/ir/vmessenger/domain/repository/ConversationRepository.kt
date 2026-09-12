package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.Conversation
import ir.vmessenger.domain.model.ConversationSummary
import ir.vmessenger.domain.model.RecipientDelivery
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

@Suppress("TooManyFunctions") // one method per conversation-screen capability; splitting it would only hide the surface
interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>

    /**
     * The whole chat list from one JOIN (conversation + contact + last message),
     * newest activity first. Prefer this over [observeConversations]: it carries
     * the preview kind, delivery state and mute flag the list actually renders.
     */
    fun observeChatList(): Flow<List<ConversationSummary>>

    /** Every message of a conversation, oldest first. Unbounded — use the windowed overload for the chat screen. */
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>

    /**
     * The newest [limit] messages, **newest first** (index 0 is the newest), each
     * carrying its reply preview and last delivery error. The chat screen renders
     * it with `reverseLayout = true` and grows [limit] to page backwards.
     */
    fun observeMessages(conversationId: String, limit: Int): Flow<List<ChatMessage>>

    /** Total messages in the conversation; `count > limit` is what "load earlier" tests. */
    suspend fun countMessages(conversationId: String): Int

    /**
     * Zero-based position of [messageId] in the newest-first order, or -1 when it
     * is not in the conversation. Grow the window past it, then scroll to it.
     */
    suspend fun indexOfMessage(conversationId: String, messageId: String): Int

    suspend fun getOrCreateConversation(contactId: String): String
    suspend fun sendMessage(conversationId: String, text: String): AppResult<String>

    /** [replyToMessageId] is carried to the peer in the envelope and quoted in both chats. */
    suspend fun sendMessage(conversationId: String, text: String, replyToMessageId: String?): AppResult<String>

    /**
     * Queues a photo/video/file for delivery. [sourceUri] is a content Uri from
     * the system picker; the file is copied (encrypted) into app-private storage first.
     */
    suspend fun sendAttachment(conversationId: String, sourceUri: String): AppResult<String>

    /**
     * Queues a recorded voice message. [filePath] is a plaintext file the recorder
     * wrote into the cache; it is encrypted into app-private storage and deleted.
     * [waveform] is 64 amplitude buckets (0..255) drawn in the bubble, and
     * [durationMs] is shown before the audio finishes arriving on the other side.
     */
    suspend fun sendVoice(
        conversationId: String,
        filePath: String,
        durationMs: Long,
        waveform: ByteArray,
    ): AppResult<String>
    suspend fun markConversationRead(conversationId: String)

    /** Records the first listen of a voice message, which clears its "unplayed" dot for good. */
    suspend fun markVoicePlayed(messageId: String)

    /**
     * Per-member delivery state of an outgoing message, creator-name-resolved. Empty for a
     * message that was never queued (an incoming one, or a system line).
     */
    suspend fun deliveryInfo(messageId: String): List<RecipientDelivery>

    /** Removes the local copy only; nothing is sent to the peer and their copy stays. */
    suspend fun deleteMessageForMe(messageId: String)

    /** Deletes the conversation with its messages, queued sends and saved draft. */
    suspend fun deleteConversation(conversationId: String)

    suspend fun setMuted(conversationId: String, muted: Boolean)

    /** Clears the backoff of a failed/queued message so it is retried right now. */
    suspend fun retry(messageId: String)

    /** Unsent composer text, empty when there is none. */
    fun observeDraft(conversationId: String): Flow<String>

    /** Blank [text] deletes the draft. */
    suspend fun saveDraft(conversationId: String, text: String)

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
