package ir.vmessenger.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.app.locale.AppLocaleController
import ir.vmessenger.core.common.text.VmLocale
import ir.vmessenger.domain.usecase.chat.StartConversationUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val startConversation: StartConversationUseCase,
    private val appLocaleController: AppLocaleController,
) : ViewModel() {
    private val _openConversationId = MutableStateFlow<String?>(null)
    val openConversationId: StateFlow<String?> = _openConversationId.asStateFlow()

    /**
     * Switches the app's language. AppCompat recreates the activity, so there is no state to
     * publish here — whatever composes next reads the new value on its way up.
     */
    fun setLanguage(locale: VmLocale) = appLocaleController.set(locale)

    fun startChat(contactId: String) {
        viewModelScope.launch {
            _openConversationId.value = startConversation(contactId)
        }
    }

    fun consumeOpenConversation() {
        _openConversationId.value = null
    }
}
