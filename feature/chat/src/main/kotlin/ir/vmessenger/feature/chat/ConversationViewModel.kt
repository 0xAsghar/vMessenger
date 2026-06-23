package ir.vmessenger.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private var conversationId: String? = null

    fun load(conversationId: String) {
        this.conversationId = conversationId
        viewModelScope.launch {
            conversationRepository.observeMessages(conversationId).collect { list ->
                _messages.value = list
            }
        }
    }

    fun send(text: String) {
        val cid = conversationId ?: return
        viewModelScope.launch {
            conversationRepository.sendMessage(cid, text)
        }
    }
}
