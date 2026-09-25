package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import kotlinx.coroutines.flow.Flow

/**
 * Lets users add, remove, enable/disable, import, and export bootstrap/relay
 * nodes, turning app-owned infrastructure into community-operated infrastructure
 *.
 */
interface NodeManagementRepository {
    fun observeNodes(): Flow<List<NetworkNode>>

    /**
     * Adds a node from a pasted `vmnode:` link or a raw address. When [input] is a
     * link the role encoded in the link wins; otherwise [fallbackRole] is used.
     */
    suspend fun addNode(
        input: String,
        fallbackRole: NetworkNodeRole,
        mode: NodeAddMode = NodeAddMode.ReplaceByLocation,
    ): AppResult<NetworkNode>

    suspend fun setEnabled(address: String, role: NetworkNodeRole, enabled: Boolean)

    suspend fun removeNode(address: String, role: NetworkNodeRole): AppResult<Unit>

    fun exportLink(node: NetworkNode): String
}

/**
 * What adding an address does when the same node location (scheme, host, port, path) is already
 * stored with another key pin.
 */
enum class NodeAddMode {
    /** The person added it: their address replaces the stored one — a node's new key, or a fix. */
    ReplaceByLocation,

    /** A backup being restored: what is stored now is newer than the backup, so it stays. */
    KeepExisting,
}
