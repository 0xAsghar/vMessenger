package ir.vmessenger.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            conversationRepository.sendMessage(conversationId, trimmed)
        }
    }
}
