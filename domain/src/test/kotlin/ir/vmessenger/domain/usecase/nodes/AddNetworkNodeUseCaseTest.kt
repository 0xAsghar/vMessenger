package ir.vmessenger.domain.usecase.nodes

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.NodeAddressRejection
import ir.vmessenger.core.common.network.NodeTrust
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.network.NodeLinkCodec
import ir.vmessenger.domain.repository.NodeAddMode
import ir.vmessenger.domain.repository.NodeManagementRepository
import ir.vmessenger.domain.repository.RelayControl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddNetworkNodeUseCaseTest {
    private val repository = RecordingRepository()
    private val relayControl = CountingRelayControl()
    private val addNode = AddNetworkNodeUseCase(repository, relayControl)

    @Test
    fun `one link adds the node's other half without replacing a stored pin, and moves the relay once`() {
        val relay = "vmnode:relay:wss://node.example.org/relay"
        val bootstrap = "vmnode:bootstrap:wss://node.example.org/dht"
        runBlocking { addNode(relay, NetworkNodeRole.RELAY) }

        assertEquals(
            listOf(
                Added(relay, NetworkNodeRole.RELAY, NodeAddMode.ReplaceByLocation),
                Added(bootstrap, NetworkNodeRole.BOOTSTRAP, NodeAddMode.KeepExisting),
            ),
            repository.added,
        )
        assertEquals(1, relayControl.reselects)
    }

    @Test
    fun `a plain dht address is a bootstrap node whatever role the screen assumed`() {
        runBlocking { addNode("wss://node.example.org/dht", NetworkNodeRole.RELAY) }

        assertEquals(NetworkNodeRole.BOOTSTRAP, repository.added.first().fallbackRole)
        assertEquals(NetworkNodeRole.RELAY, repository.added.last().fallbackRole)
    }

    @Test
    fun `an address on another path is added alone`() {
        runBlocking { addNode("wss://node.example.org/custom", NetworkNodeRole.RELAY) }

        assertEquals(1, repository.added.size)
        assertEquals(1, relayControl.reselects)
    }

    @Test
    fun `a refused address adds nothing else and moves nothing`() {
        repository.refuse = true

        val result = runBlocking { addNode("ws://node.example.org/relay", NetworkNodeRole.RELAY) }

        assertTrue(result is AppResult.Error)
        assertEquals(1, repository.added.size)
        assertEquals(0, relayControl.reselects)
    }

    private data class Added(val input: String, val fallbackRole: NetworkNodeRole, val mode: NodeAddMode)

    private class RecordingRepository : NodeManagementRepository {
        val added = mutableListOf<Added>()
        var refuse = false

        override fun observeNodes(): Flow<List<NetworkNode>> = emptyFlow()

        override suspend fun addNode(
            input: String,
            fallbackRole: NetworkNodeRole,
            mode: NodeAddMode,
        ): AppResult<NetworkNode> {
            added += Added(input, fallbackRole, mode)
            if (refuse) {
                val rejection = NodeAddressRejection.INSECURE_NOT_LOCAL
                return AppResult.Error(AppError.NodeAddressRejected(rejection, relay = true))
            }
            val link = NodeLinkCodec.decode(input)
            val node = NetworkNode(
                address = link?.address ?: input,
                role = link?.role ?: fallbackRole,
                source = NetworkNode.SOURCE_USER,
                enabled = true,
                lastOkUnixMs = null,
                lastFailUnixMs = null,
                failCount = 0,
                trust = NodeTrust.USER,
            )
            return AppResult.Success(node)
        }

        override suspend fun setEnabled(address: String, role: NetworkNodeRole, enabled: Boolean) = Unit

        override suspend fun removeNode(address: String, role: NetworkNodeRole): AppResult<Unit> =
            AppResult.Success(Unit)

        override fun exportLink(node: NetworkNode): String = NodeLinkCodec.encode(node.role, node.address)
    }

    private class CountingRelayControl : RelayControl {
        var reselects = 0

        override fun reselectRelay() {
            reselects++
        }
    }
}
