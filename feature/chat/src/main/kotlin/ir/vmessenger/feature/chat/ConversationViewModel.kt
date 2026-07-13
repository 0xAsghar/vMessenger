package ir.vmessenger.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
}
