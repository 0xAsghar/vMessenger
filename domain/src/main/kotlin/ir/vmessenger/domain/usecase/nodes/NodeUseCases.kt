package ir.vmessenger.domain.usecase.nodes

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.repository.NodeManagementRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveNetworkNodesUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
) {
    operator fun invoke(): Flow<List<NetworkNode>> = repository.observeNodes()
}

class AddNetworkNodeUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
) {
    suspend operator fun invoke(
        input: String,
        fallbackRole: NetworkNodeRole,
    ): AppResult<NetworkNode> = repository.addNode(input, fallbackRole)
}

class SetNetworkNodeEnabledUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
) {
    suspend operator fun invoke(address: String, role: NetworkNodeRole, enabled: Boolean) =
        repository.setEnabled(address, role, enabled)
}

class RemoveNetworkNodeUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
) {
    suspend operator fun invoke(address: String, role: NetworkNodeRole): AppResult<Unit> =
        repository.removeNode(address, role)
}

class ExportNetworkNodeLinkUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
) {
    operator fun invoke(node: NetworkNode): String = repository.exportLink(node)
}
