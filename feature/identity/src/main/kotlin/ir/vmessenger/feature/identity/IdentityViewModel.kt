package ir.vmessenger.feature.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.usecase.identity.GenerateIdentityUseCase
import ir.vmessenger.domain.usecase.identity.GetIdentityUseCase
import ir.vmessenger.domain.usecase.identity.UpdateDisplayNameUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CreateIdentityUiState {
    data object Intro : CreateIdentityUiState()
    data class NameEntry(val displayName: String = "", val error: String? = null) : CreateIdentityUiState()
    data object Creating : CreateIdentityUiState()
    data class Success(val identity: Identity) : CreateIdentityUiState()
    data class Error(val message: String) : CreateIdentityUiState()
}

@HiltViewModel
class CreateIdentityViewModel @Inject constructor(
    private val generateIdentityUseCase: GenerateIdentityUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<CreateIdentityUiState>(CreateIdentityUiState.Intro)
    val uiState: StateFlow<CreateIdentityUiState> = _uiState.asStateFlow()

    fun onIntroContinue() {
        _uiState.value = CreateIdentityUiState.NameEntry()
    }

    fun onDisplayNameChange(name: String) {
        val current = _uiState.value
        if (current is CreateIdentityUiState.NameEntry) {
            _uiState.value = current.copy(displayName = name, error = null)
        }
    }

    fun createIdentity() {
        val current = _uiState.value
        if (current !is CreateIdentityUiState.NameEntry) return
        val trimmed = current.displayName.trim()
        if (trimmed.length !in DISPLAY_NAME_MIN..DISPLAY_NAME_MAX) {
            _uiState.value = current.copy(error = "نام باید بین $DISPLAY_NAME_MIN تا $DISPLAY_NAME_MAX کاراکتر باشد")
            return
        }
        if (_uiState.value is CreateIdentityUiState.Creating) return
        viewModelScope.launch {
            _uiState.value = CreateIdentityUiState.Creating
            when (val result = generateIdentityUseCase(trimmed)) {
                is AppResult.Success -> _uiState.value = CreateIdentityUiState.Success(result.data)
                is AppResult.Error -> _uiState.value = CreateIdentityUiState.Error(result.error.message)
            }
        }
    }

    fun retryFromError() {
        _uiState.value = CreateIdentityUiState.NameEntry()
    }

    companion object {
        const val DISPLAY_NAME_MIN = 2
        const val DISPLAY_NAME_MAX = 32
    }
}

sealed class IdentityUiState {
    data object Loading : IdentityUiState()
    data class Loaded(val identity: Identity) : IdentityUiState()
    data object None : IdentityUiState()
}

@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val getIdentityUseCase: GetIdentityUseCase,
    private val updateDisplayNameUseCase: UpdateDisplayNameUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<IdentityUiState>(IdentityUiState.Loading)
    val uiState: StateFlow<IdentityUiState> = _uiState.asStateFlow()

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
        viewModelScope.launch {
            when (updateDisplayNameUseCase(name)) {
                is AppResult.Success -> {
                    val identity = getIdentityUseCase()
                    if (identity != null) {
                        _uiState.value = IdentityUiState.Loaded(identity)
                    }
                }
                is AppResult.Error -> Unit
            }
        }
    }
}
