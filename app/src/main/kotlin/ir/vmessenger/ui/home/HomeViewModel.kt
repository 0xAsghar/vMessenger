package ir.vmessenger.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.usecase.chat.StartConversationUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val startConversation: StartConversationUseCase,
) : ViewModel() {
    private val _openConversationId = MutableStateFlow<String?>(null)
    val openConversationId: StateFlow<String?> = _openConversationId.asStateFlow()

    fun startChat(contactId: String) {
        viewModelScope.launch {
            _openConversationId.value = startConversation(contactId)
        }
    }

    fun consumeOpenConversation() {
        _openConversationId.value = null
    }
}
