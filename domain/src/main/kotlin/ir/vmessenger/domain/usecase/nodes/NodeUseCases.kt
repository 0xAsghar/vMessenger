package ir.vmessenger.domain.usecase.nodes

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.repository.NodeManagementRepository
import ir.vmessenger.domain.repository.RelayControl
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveNetworkNodesUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
) {
    operator fun invoke(): Flow<List<NetworkNode>> = repository.observeNodes()
}

/**
 * A node change takes effect at once: the relay listener moves and the published record follows it,
 * and the DHT drops a bootstrap node switched off and joins through one added.
 */
class AddNetworkNodeUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
    private val relayControl: RelayControl,
) {
    suspend operator fun invoke(
        input: String,
        fallbackRole: NetworkNodeRole,
    ): AppResult<NetworkNode> = repository.addNode(input, fallbackRole).also { result ->
        if (result is AppResult.Success) relayControl.reselectRelay()
    }
}

class SetNetworkNodeEnabledUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
    private val relayControl: RelayControl,
) {
    suspend operator fun invoke(address: String, role: NetworkNodeRole, enabled: Boolean) {
        repository.setEnabled(address, role, enabled)
        relayControl.reselectRelay()
    }
}

class RemoveNetworkNodeUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
    private val relayControl: RelayControl,
) {
    suspend operator fun invoke(address: String, role: NetworkNodeRole): AppResult<Unit> =
        repository.removeNode(address, role).also { result ->
            if (result is AppResult.Success) relayControl.reselectRelay()
        }
}

class ExportNetworkNodeLinkUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
) {
    operator fun invoke(node: NetworkNode): String = repository.exportLink(node)
}
