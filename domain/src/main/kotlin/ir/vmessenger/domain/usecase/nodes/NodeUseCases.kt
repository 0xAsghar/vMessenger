package ir.vmessenger.domain.usecase.nodes

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.network.NodeLinkCodec
import ir.vmessenger.domain.repository.NodeAddMode
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
 * Adds a node from one address and, when that address is a vMessenger node's `/relay` or `/dht`, its
 * other half too ([NodeLinkCodec.companionOf]): a phone needs both a relay and a bootstrap node to be
 * reachable, and the person usually has one link. A plain address on `/dht` is a bootstrap node
 * whatever [fallbackRole] says. The other half never replaces a row already stored at its location
 * (its key pin is the person's to change), and failing to add it leaves the first one added.
 *
 * Like every node change it takes effect at once: the relay listener moves and the published record
 * follows it, and the DHT joins through the new bootstrap node.
 */
class AddNetworkNodeUseCase @Inject constructor(
    private val repository: NodeManagementRepository,
    private val relayControl: RelayControl,
) {
    suspend operator fun invoke(input: String, fallbackRole: NetworkNodeRole): AppResult<NetworkNode> {
        val added = repository.addNode(input, NodeLinkCodec.roleOfPath(input) ?: fallbackRole)
        if (added is AppResult.Success) {
            NodeLinkCodec.companionOf(added.data.role, added.data.address)?.let { other ->
                val link = NodeLinkCodec.encode(other.role, other.address)
                repository.addNode(link, other.role, NodeAddMode.KeepExisting)
            }
            relayControl.reselectRelay()
        }
        return added
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
