package ir.vmessenger.domain.usecase.nodes

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.ManagedNode
import ir.vmessenger.domain.model.ManagedNodeStatus
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.repository.ManagedNodeRepository
import ir.vmessenger.domain.repository.NodeManagementRepository
import ir.vmessenger.domain.repository.RelayControl
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveManagedNodesUseCase @Inject constructor(private val repository: ManagedNodeRepository) {
    operator fun invoke(): Flow<List<ManagedNode>> = repository.observe()
}

class GetManagedNodeUseCase @Inject constructor(private val repository: ManagedNodeRepository) {
    suspend operator fun invoke(id: String): ManagedNode? = repository.get(id)
}

/** Forgets a server; with [alsoRemoveNodes], its relay and bootstrap rows go too. */
class ForgetManagedNodeUseCase @Inject constructor(
    private val repository: ManagedNodeRepository,
    private val nodes: NodeManagementRepository,
    private val relayControl: RelayControl,
) {
    suspend operator fun invoke(node: ManagedNode, alsoRemoveNodes: Boolean) {
        repository.forget(node.id)
        if (alsoRemoveNodes) {
            nodes.removeNode(node.relayUrl, NetworkNodeRole.RELAY)
            nodes.removeNode(node.bootstrapUrl, NetworkNodeRole.BOOTSTRAP)
            relayControl.reselectRelay()
        }
    }
}

/**
 * A setup finished: the node's bootstrap and relay addresses join the app's nodes (through the same
 * checks as any address the person adds — a location already stored gets the new pin), the relay
 * enabled only when [useAsRelay], the server recorded as READY, and the relay listener moved.
 */
class CompleteNodeProvisioningUseCase @Inject constructor(
    private val repository: ManagedNodeRepository,
    private val nodes: NodeManagementRepository,
    private val relayControl: RelayControl,
) {
    suspend operator fun invoke(node: ManagedNode, useAsRelay: Boolean): AppResult<ManagedNode> {
        val added = listOf(node.bootstrapUrl to NetworkNodeRole.BOOTSTRAP, node.relayUrl to NetworkNodeRole.RELAY)
            .map { (url, role) -> nodes.addNode(url, role) }
        val failed = added.firstOrNull { it is AppResult.Error } as AppResult.Error?
        if (failed != null) return failed
        if (!useAsRelay) nodes.setEnabled(node.relayUrl, NetworkNodeRole.RELAY, enabled = false)
        val ready = node.copy(status = ManagedNodeStatus.READY)
        repository.save(ready)
        relayControl.reselectRelay()
        return AppResult.Success(ready)
    }
}
