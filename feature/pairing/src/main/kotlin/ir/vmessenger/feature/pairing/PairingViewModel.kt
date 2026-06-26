package ir.vmessenger.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.domain.repository.PairingRepository
import ir.vmessenger.domain.usecase.contact.AddContactByHashUseCase
import ir.vmessenger.domain.usecase.contact.AddContactByQrUseCase
import ir.vmessenger.domain.usecase.identity.GetIdentityUseCase
import ir.vmessenger.domain.usecase.pairing.CreateMyPairingDescriptorUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class MyQrUiState {
    data object Loading : MyQrUiState()
    data class Ready(val userHash: String, val qrPayload: String) : MyQrUiState()
    data object NoIdentity : MyQrUiState()
}

@HiltViewModel
class MyQrViewModel @Inject constructor(
    private val createDescriptor: CreateMyPairingDescriptorUseCase,
    private val pairingRepository: PairingRepository,
    private val getIdentity: GetIdentityUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MyQrUiState>(MyQrUiState.Loading)
    val uiState: StateFlow<MyQrUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bytes = createDescriptor()
            val identity = getIdentity()
            _uiState.value = if (bytes != null && identity != null) {
                MyQrUiState.Ready(identity.userHash, pairingRepository.encodeDescriptor(bytes))
            } else {
                MyQrUiState.NoIdentity
            }
        }
    }
}

sealed class AddContactUiState {
    data object Idle : AddContactUiState()
    data object Saving : AddContactUiState()
    data object Success : AddContactUiState()
    data class Error(val message: String) : AddContactUiState()
}

@HiltViewModel
class AddByHashViewModel @Inject constructor(
    private val addByHash: AddContactByHashUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AddContactUiState>(AddContactUiState.Idle)
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    fun addContact(userHash: String) {
        val trimmed = userHash.trim()
        if (!UserHashEncoder.isValid(trimmed)) {
            val reason = UserHashEncoder.decodeFailureReason(trimmed)
            AppLogger.warn(
                "Pairing",
                "addByHash rejected reason=$reason len=${trimmed.length} " +
                    "prefix=${trimmed.take(12)}",
            )
            _uiState.value = AddContactUiState.Error("شناسه کاربری نامعتبر است")
            return
        }
        viewModelScope.launch {
            _uiState.value = AddContactUiState.Saving
            when (val result = addByHash(trimmed)) {
                is AppResult.Success -> _uiState.value = AddContactUiState.Success
                is AppResult.Error -> _uiState.value = AddContactUiState.Error(result.error.message)
            }
        }
    }
}

@HiltViewModel
class QrScanViewModel @Inject constructor(
    private val addByQr: AddContactByQrUseCase,
    private val pairingRepository: PairingRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AddContactUiState>(AddContactUiState.Idle)
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    fun onQrScanned(payload: String) {
        val descriptorBytes = pairingRepository.decodeDescriptor(payload.trim())
            ?: run {
                _uiState.value = AddContactUiState.Error("QR نامعتبر است")
                return
            }
        viewModelScope.launch {
            _uiState.value = AddContactUiState.Saving
            when (val result = addByQr(descriptorBytes)) {
                is AppResult.Success -> _uiState.value = AddContactUiState.Success
                is AppResult.Error -> _uiState.value = AddContactUiState.Error(result.error.message)
            }
        }
    }
}
