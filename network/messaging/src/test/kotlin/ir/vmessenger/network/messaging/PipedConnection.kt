package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.ConnectionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** In-memory [Connection]; [outgoingTransform] lets a test interpose an active attacker. */
internal class PipedConnection(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
    private val outgoingTransform: (ByteArray) -> ByteArray = { it },
) : Connection {
    private val _state = MutableStateFlow(ConnectionState.OPEN)
    override val remote = Endpoint(TransportIds.INTERNET, "piped://test")
    override val state: StateFlow<ConnectionState> = _state

    override suspend fun write(frame: ByteArray): Result<Unit> = runCatching {
        outgoing.send(outgoingTransform(frame))
    }

    override fun read(): Flow<ByteArray> = incoming.receiveAsFlow()

    override suspend fun close() {
        _state.value = ConnectionState.CLOSED
        incoming.close()
        outgoing.close()
    }
}

/**
 * Two ends of an in-memory pipe as (client, server). [serverToClient] /
 * [clientToServer] rewrite bytes in that direction.
 */
internal fun pairedConnections(
    serverToClient: (ByteArray) -> ByteArray = { it },
    clientToServer: (ByteArray) -> ByteArray = { it },
): Pair<PipedConnection, PipedConnection> {
    val c2s = Channel<ByteArray>(64)
    val s2c = Channel<ByteArray>(64)
    return PipedConnection(s2c, c2s, clientToServer) to PipedConnection(c2s, s2c, serverToClient)
}
