package ir.vmessenger.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.network.NodeLinkCodec
import ir.vmessenger.domain.usecase.nodes.AddNetworkNodeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class NodeScanUiState {
    data object Idle : NodeScanUiState()
    data object Saving : NodeScanUiState()
    data object Success : NodeScanUiState()
    data class Error(val message: String) : NodeScanUiState()
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
            _uiState.value = NodeScanUiState.Error(
                "QR نامعتبر است. لینک vmnode:bootstrap:… یا vmnode:relay:… را اسکن کنید",
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = NodeScanUiState.Saving
            when (val result = addNetworkNode(trimmed, link.role)) {
                is AppResult.Success -> _uiState.value = NodeScanUiState.Success
                is AppResult.Error -> _uiState.value = NodeScanUiState.Error(result.error.message)
            }
        }
    }
}
