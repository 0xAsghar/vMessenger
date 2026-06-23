package ir.vmessenger.network.transport

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState {
    CONNECTING,
    OPEN,
    CLOSED,
    FAILED,
}

interface Connection {
    val remote: Endpoint
    val state: StateFlow<ConnectionState>
    suspend fun write(frame: ByteArray): Result<Unit>
    fun read(): Flow<ByteArray>
    suspend fun close()
}

data class TransportCapabilities(
    val reliable: Boolean,
    val ordered: Boolean,
    val mtu: Int,
)

interface Transport {
    val id: TransportId
    val capabilities: TransportCapabilities
    fun canReach(endpoint: Endpoint): Boolean
    suspend fun connect(endpoint: Endpoint): Result<Connection>
    fun listen(port: Int): Flow<Connection>
}
