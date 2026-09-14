package ir.vmessenger.feature.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.designsystem.component.UiMessage
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.usecase.identity.GetIdentityUseCase
import ir.vmessenger.domain.usecase.identity.UpdateDisplayNameUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class IdentityUiState {
    data object Loading : IdentityUiState()

    /** [saving] holds the name field and its button while the write is in flight. */
    data class Loaded(val identity: Identity, val saving: Boolean = false) : IdentityUiState()
    data object None : IdentityUiState()
}

@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val getIdentityUseCase: GetIdentityUseCase,
    private val updateDisplayNameUseCase: UpdateDisplayNameUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<IdentityUiState>(IdentityUiState.Loading)
    val uiState: StateFlow<IdentityUiState> = _uiState.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)

    /**
     * Snackbars for the one thing this screen writes. Both outcomes are emitted: a silent failure
     * left the user staring at a name that looked saved and was not.
     */
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val identity = getIdentityUseCase()
            _uiState.value = if (identity != null) {
                IdentityUiState.Loaded(identity)
            } else {
                IdentityUiState.None
            }
        }
    }

    fun updateDisplayName(name: String) {
        val current = _uiState.value
        if (current !is IdentityUiState.Loaded || current.saving) return
        _uiState.value = current.copy(saving = true)
        viewModelScope.launch {
            val message = when (val result = updateDisplayNameUseCase(name)) {
                is AppResult.Success -> UiMessage.Text(R.string.my_identity_display_name_saved)
                is AppResult.Error -> UiMessage.Failure(result.error)
            }
            // Re-read instead of patching the name in place: the repository is the only thing
            // that knows what was actually stored, and on failure that is the old name.
            val identity = getIdentityUseCase()
            _uiState.value = if (identity != null) {
                IdentityUiState.Loaded(identity)
            } else {
                current.copy(saving = false)
            }
            _messages.send(message)
        }
    }
}
