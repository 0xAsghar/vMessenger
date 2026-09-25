package ir.vmessenger.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.nodesetup.InstallerBundle
import ir.vmessenger.domain.model.ManagedNode
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.usecase.nodes.AddNetworkNodeUseCase
import ir.vmessenger.domain.usecase.nodes.ExportNetworkNodeLinkUseCase
import ir.vmessenger.domain.usecase.nodes.ForgetManagedNodeUseCase
import ir.vmessenger.domain.usecase.nodes.ObserveManagedNodesUseCase
import ir.vmessenger.domain.usecase.nodes.ObserveNetworkNodesUseCase
import ir.vmessenger.domain.usecase.nodes.RemoveNetworkNodeUseCase
import ir.vmessenger.domain.usecase.nodes.SetNetworkNodeEnabledUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NodesUiState(
    val bootstrapNodes: List<NetworkNode> = emptyList(),
    val relayNodes: List<NetworkNode> = emptyList(),
    /** "Your servers": the ones this device set up, and whether the app carries a newer node for each. */
    val servers: List<ManagedServer> = emptyList(),
)

data class ManagedServer(val node: ManagedNode, val canUpdate: Boolean)

@HiltViewModel
@Suppress("LongParameterList") // one use case per thing the Nodes screen does, and the servers this device set up
class NodesViewModel @Inject constructor(
    observeNetworkNodes: ObserveNetworkNodesUseCase,
    private val addNetworkNode: AddNetworkNodeUseCase,
    private val setNetworkNodeEnabled: SetNetworkNodeEnabledUseCase,
    private val removeNetworkNode: RemoveNetworkNodeUseCase,
    private val exportNetworkNodeLink: ExportNetworkNodeLinkUseCase,
    observeManagedNodes: ObserveManagedNodesUseCase,
    private val forgetManagedNode: ForgetManagedNodeUseCase,
    private val installerBundle: InstallerBundle,
) : ViewModel() {
    val uiState: StateFlow<NodesUiState> = combine(observeNetworkNodes(), observeManagedNodes()) { nodes, servers ->
        val bundled = runCatching { installerBundle.nodeVersion }.getOrNull()
        NodesUiState(
            bootstrapNodes = nodes.filter { it.role == NetworkNodeRole.BOOTSTRAP },
            relayNodes = nodes.filter { it.role == NetworkNodeRole.RELAY },
            servers = servers.map { ManagedServer(it, bundled != null && it.canUpdateTo(bundled)) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NodesUiState())

    private val _addError = MutableStateFlow<AppError?>(null)

    /** What went wrong, not a sentence: the screen words it in the app's language. */
    val addError: StateFlow<AppError?> = _addError.asStateFlow()

    /** [onAdded] runs once the node is stored — the dialog closes then, and stays open on an error. */
    fun addNode(input: String, role: NetworkNodeRole, onAdded: () -> Unit = {}) {
        viewModelScope.launch {
            when (val result = addNetworkNode(input, role)) {
                is AppResult.Success -> {
                    _addError.value = null
                    onAdded()
                }
                is AppResult.Error -> _addError.value = result.error
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
                is AppResult.Error -> _addError.value = result.error
            }
        }
    }

    fun exportLink(node: NetworkNode): String = exportNetworkNodeLink(node)

    /** Forgets a server this device set up; its addresses stay among the nodes unless [alsoRemoveNodes]. */
    fun forget(server: ManagedNode, alsoRemoveNodes: Boolean) {
        viewModelScope.launch { forgetManagedNode(server, alsoRemoveNodes) }
    }
}
