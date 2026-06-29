package ir.vmessenger.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.usecase.nodes.AddNetworkNodeUseCase
import ir.vmessenger.domain.usecase.nodes.ExportNetworkNodeLinkUseCase
import ir.vmessenger.domain.usecase.nodes.ObserveNetworkNodesUseCase
import ir.vmessenger.domain.usecase.nodes.RemoveNetworkNodeUseCase
import ir.vmessenger.domain.usecase.nodes.SetNetworkNodeEnabledUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NodesUiState(
    val bootstrapNodes: List<NetworkNode> = emptyList(),
    val relayNodes: List<NetworkNode> = emptyList(),
)

@HiltViewModel
class NodesViewModel @Inject constructor(
    observeNetworkNodes: ObserveNetworkNodesUseCase,
    private val addNetworkNode: AddNetworkNodeUseCase,
    private val setNetworkNodeEnabled: SetNetworkNodeEnabledUseCase,
    private val removeNetworkNode: RemoveNetworkNodeUseCase,
    private val exportNetworkNodeLink: ExportNetworkNodeLinkUseCase,
) : ViewModel() {
    val uiState: StateFlow<NodesUiState> = observeNetworkNodes()
        .map { nodes ->
            NodesUiState(
                bootstrapNodes = nodes.filter { it.role == NetworkNodeRole.BOOTSTRAP },
                relayNodes = nodes.filter { it.role == NetworkNodeRole.RELAY },
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NodesUiState())

    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    fun addNode(input: String, role: NetworkNodeRole) {
        viewModelScope.launch {
            when (val result = addNetworkNode(input, role)) {
                is AppResult.Success -> _addError.value = null
                is AppResult.Error -> _addError.value = result.error.message
            }
        }
    }

    fun clearAddError() {
        _addError.value = null
    }

    fun setEnabled(node: NetworkNode, enabled: Boolean) {
        viewModelScope.launch { setNetworkNodeEnabled(node.address, node.role, enabled) }
    }

    fun remove(node: NetworkNode) {
        viewModelScope.launch {
            when (val result = removeNetworkNode(node.address, node.role)) {
                is AppResult.Success -> Unit
                is AppResult.Error -> _addError.value = result.error.message
            }
        }
    }

    fun exportLink(node: NetworkNode): String = exportNetworkNodeLink(node)
}
