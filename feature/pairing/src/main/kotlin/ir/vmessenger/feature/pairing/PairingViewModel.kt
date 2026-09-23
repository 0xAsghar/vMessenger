package ir.vmessenger.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.designsystem.component.UiMessage
import ir.vmessenger.core.designsystem.component.UiMessageBus
import ir.vmessenger.domain.repository.PairingRepository
import ir.vmessenger.domain.usecase.contact.AddContactByHashUseCase
import ir.vmessenger.domain.usecase.contact.AddContactByQrUseCase
import ir.vmessenger.domain.usecase.identity.GetIdentityUseCase
import ir.vmessenger.domain.usecase.pairing.CreateMyPairingDescriptorUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class MyQrUiState {
    data object Loading : MyQrUiState()
    data class Ready(val userHash: String, val qrPayload: String) : MyQrUiState()
    data object NoIdentity : MyQrUiState()
    data object Error : MyQrUiState()
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
            _uiState.value = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = createDescriptor()
                    val identity = getIdentity()
                    when {
                        bytes != null && identity != null ->
                            MyQrUiState.Ready(
                                userHash = identity.userHash,
                                qrPayload = pairingRepository.encodeDescriptor(bytes),
                            )
                        else -> MyQrUiState.NoIdentity
                    }
                }.getOrElse { MyQrUiState.Error }
            }
        }
    }
}

sealed class AddContactUiState {
    data object Idle : AddContactUiState()
    data object Saving : AddContactUiState()
    data object Success : AddContactUiState()

    /** Carries the code, not a sentence: the Persian text comes from `AppError.toUiText()`. */
    data class Error(val error: AppError) : AddContactUiState()

    /** Whether a fresh scan should be acted on, or ignored as a repeat of one already in flight. */
    val acceptsScan: Boolean get() = this is Idle || this is Error
}

@HiltViewModel
class AddByHashViewModel @Inject constructor(
    private val addByHash: AddContactByHashUseCase,
    private val getIdentity: GetIdentityUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AddContactUiState>(AddContactUiState.Idle)
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    private val _ownUserHash = MutableStateFlow<String?>(null)

    /** The user's own ID, so the field can say so as it is pasted rather than after Add fails. */
    val ownUserHash: StateFlow<String?> = _ownUserHash.asStateFlow()

    init {
        viewModelScope.launch { _ownUserHash.value = getIdentity()?.userHash }
    }

    /** An edit answers the last failure: the form is back to asking, with its button. */
    fun onInputChanged() {
        if (_uiState.value is AddContactUiState.Error) _uiState.value = AddContactUiState.Idle
    }

    fun addContact(userHash: String) {
        val trimmed = userHash.trim()
        if (!UserHashEncoder.isValid(trimmed)) {
            val reason = UserHashEncoder.decodeFailureReason(trimmed)
            AppLogger.warn(
                "Pairing",
                "addByHash rejected reason=$reason len=${trimmed.length} " +
                    "prefix=${trimmed.take(12)}",
            )
            _uiState.value = AddContactUiState.Error(AppError.InvalidUserHash)
            return
        }
        viewModelScope.launch {
            _uiState.value = AddContactUiState.Saving
            when (val result = addByHash(trimmed)) {
                is AppResult.Success -> _uiState.value = AddContactUiState.Success
                is AppResult.Error -> _uiState.value = AddContactUiState.Error(result.error)
            }
        }
    }
}

/**
 * The contact-QR scanner.
 *
 * The outcome is published to [UiMessageBus] rather than drawn here, because this screen closes
 * itself the moment it has an answer: a snackbar hosted by a composition that is about to be
 * popped would race the pop and usually lose. The message surfaces on the screen underneath.
 */
@HiltViewModel
class QrScanViewModel @Inject constructor(
    private val addByQr: AddContactByQrUseCase,
    private val pairingRepository: PairingRepository,
    private val messageBus: UiMessageBus,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AddContactUiState>(AddContactUiState.Idle)
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    /**
     * ML Kit reports a barcode for *every* analysed frame, and the camera stays live until the
     * state settles — so without this guard a single held-up QR code fired ten to thirty contact
     * requests a second at the peer.
     */
    fun onQrScanned(payload: String) {
        if (!_uiState.value.acceptsScan) return
        val descriptorBytes = pairingRepository.decodeDescriptor(payload.trim())
        if (descriptorBytes == null) {
            fail(AppError.InvalidQr)
            return
        }
        viewModelScope.launch {
            _uiState.value = AddContactUiState.Saving
            when (val result = addByQr(descriptorBytes)) {
                is AppResult.Success -> {
                    // Re-scanning someone already in the list must not claim they have to approve us again.
                    val text = if (result.data.isApproved) {
                        R.string.add_contact_already_approved
                    } else {
                        R.string.add_contact_success
                    }
                    messageBus.send(UiMessage.Text(text))
                    _uiState.value = AddContactUiState.Success
                }
                is AppResult.Error -> fail(result.error)
            }
        }
    }

    private fun fail(error: AppError) {
        _uiState.value = AddContactUiState.Error(error)
        viewModelScope.launch { messageBus.send(UiMessage.Failure(error)) }
    }
}
