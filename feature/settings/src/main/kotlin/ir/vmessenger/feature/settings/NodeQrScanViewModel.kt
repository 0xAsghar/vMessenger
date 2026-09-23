package ir.vmessenger.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.network.NodeLinkCodec
import ir.vmessenger.domain.usecase.nodes.AddNetworkNodeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Failures carry what went wrong, not a sentence: the screen words it in the app's language. This
 * used to hold a finished Persian sentence for a bad QR, and the error's developer message — English
 * by design — for everything else, whatever language the app was in.
 */
sealed class NodeScanUiState {
    data object Idle : NodeScanUiState()
    data object Saving : NodeScanUiState()
    data object Success : NodeScanUiState()

    /** A QR, but not a node link. */
    data object NotANodeLink : NodeScanUiState()
    data class Failed(val error: AppError) : NodeScanUiState()
}

@HiltViewModel
class NodeQrScanViewModel @Inject constructor(
    private val addNetworkNode: AddNetworkNodeUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<NodeScanUiState>(NodeScanUiState.Idle)
    val uiState: StateFlow<NodeScanUiState> = _uiState.asStateFlow()

    fun onQrScanned(payload: String) {
        if (_uiState.value is NodeScanUiState.Saving || _uiState.value is NodeScanUiState.Success) {
            return
        }
        val trimmed = payload.trim()
        val link = NodeLinkCodec.decode(trimmed)
        if (link == null) {
            _uiState.value = NodeScanUiState.NotANodeLink
            return
        }
        viewModelScope.launch {
            _uiState.value = NodeScanUiState.Saving
            when (val result = addNetworkNode(trimmed, link.role)) {
                is AppResult.Success -> _uiState.value = NodeScanUiState.Success
                is AppResult.Error -> _uiState.value = NodeScanUiState.Failed(result.error)
            }
        }
    }
}
