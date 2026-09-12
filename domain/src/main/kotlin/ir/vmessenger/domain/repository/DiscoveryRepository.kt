package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.domain.model.DiscoveryStatus
import kotlinx.coroutines.flow.Flow

/**
 * Joining the network and publishing this device's endpoints. Resolving *other*
 * peers is not part of this contract: `EndpointResolveService` (cache-first,
 * signature-verified, routing-hash keyed) is the single resolver.
 */
interface DiscoveryRepository {
    fun observeStatus(): Flow<DiscoveryStatus>
    suspend fun joinNetwork(): AppResult<Unit>
    suspend fun publishEndpoint(endpoint: Endpoint): AppResult<Unit>
    suspend fun publishEndpoints(endpoints: List<Endpoint>): AppResult<Unit>
    suspend fun getPublishedEndpoint(): String?
}
