package ir.vmessenger.network.transport

import ir.vmessenger.core.common.network.Endpoint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportSelector @Inject constructor(
    transports: Set<@JvmSuppressWildcards Transport>,
) {
    private val transportsById = transports.associateBy { it.id }

    fun transports(): Collection<Transport> = transportsById.values

    fun forEndpoint(endpoint: Endpoint): Transport? = transportsById[endpoint.transport]

    suspend fun connect(endpoint: Endpoint): Result<Connection> {
        val transport = forEndpoint(endpoint)
        return when {
            transport == null -> Result.failure(
                IllegalStateException("No transport for ${endpoint.transport.value}"),
            )
            !transport.canReach(endpoint) -> Result.failure(
                IllegalStateException("Transport cannot reach ${endpoint.address}"),
            )
            else -> transport.connect(endpoint)
        }
    }
}
