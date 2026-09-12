package ir.vmessenger.feature.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    val messages: StateFlow<List<ChatMessage>> = conversationRepository
        .observeMessages(conversationId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /** Contact display name for the screen title (null while loading). */
    val contactName: StateFlow<String?> = conversationRepository
        .observeConversations()
        .map { conversations -> conversations.firstOrNull { it.id == conversationId }?.contactName }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /** Attachment transfers in flight for this conversation, keyed by message id. */
    val attachmentProgress: StateFlow<Map<String, AttachmentProgress>> = conversationRepository
        .observeAttachmentProgress(conversationId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(PROGRESS_STOP_TIMEOUT_MS),
            initialValue = emptyMap(),
        )

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            conversationRepository.sendMessage(conversationId, trimmed)
        }
    }

    fun sendAttachment(uri: String) {
        viewModelScope.launch {
            conversationRepository.sendAttachment(conversationId, uri)
        }
    }

    /** Decodes a down-sampled thumbnail from the (encrypted) attachment of [messageId]. */
    suspend fun loadThumbnail(messageId: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            conversationRepository.openAttachment(messageId)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            if (largest <= 0) return@runCatching null
            var sample = 1
            while (largest / (sample * 2) >= THUMBNAIL_TARGET_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            conversationRepository.openAttachment(messageId)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    /** Exports the attachment to the view cache and hands its plaintext path to [onReady] (null on failure). */
    fun openAttachment(messageId: String, onReady: (String?) -> Unit) {
        viewModelScope.launch {
            val path = when (val result = conversationRepository.exportAttachmentForViewing(messageId)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> null
            }
            onReady(path)
        }
    }

    private companion object {
        const val PROGRESS_STOP_TIMEOUT_MS = 5_000L
        const val THUMBNAIL_TARGET_PX = 640
    }
}
