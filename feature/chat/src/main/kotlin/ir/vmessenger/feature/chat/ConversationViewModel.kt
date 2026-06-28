package ir.vmessenger.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val activeConversationId = MutableStateFlow<String?>(null)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    init {
        viewModelScope.launch {
            activeConversationId
                .filterNotNull()
                .flatMapLatest { conversationRepository.observeMessages(it) }
                .collect { list -> _messages.value = list }
        }
    }

    fun load(conversationId: String) {
        activeConversationId.value = conversationId
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val conversationId = activeConversationId.value ?: return
        viewModelScope.launch {
            conversationRepository.sendMessage(conversationId, trimmed)
        }
    }
}
