package ir.vmessenger.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val activeConversationId = MutableStateFlow<String?>(null)

    val messages: StateFlow<List<ChatMessage>> = activeConversationId
        .filterNotNull()
        .flatMapLatest { conversationRepository.observeMessages(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

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
