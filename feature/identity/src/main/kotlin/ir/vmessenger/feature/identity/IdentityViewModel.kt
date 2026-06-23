package ir.vmessenger.feature.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.usecase.identity.GenerateIdentityUseCase
import ir.vmessenger.domain.usecase.identity.GetIdentityUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CreateIdentityUiState {
    data object Intro : CreateIdentityUiState()
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

    fun createIdentity() {
        if (_uiState.value is CreateIdentityUiState.Creating) return
        viewModelScope.launch {
            _uiState.value = CreateIdentityUiState.Creating
            when (val result = generateIdentityUseCase()) {
                is AppResult.Success -> _uiState.value = CreateIdentityUiState.Success(result.data)
                is AppResult.Error -> _uiState.value = CreateIdentityUiState.Error(result.error.message)
            }
        }
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
}
