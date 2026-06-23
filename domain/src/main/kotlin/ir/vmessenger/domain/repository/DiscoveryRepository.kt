package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.domain.model.DiscoveryStatus
import kotlinx.coroutines.flow.Flow

interface DiscoveryRepository {
    fun observeStatus(): Flow<DiscoveryStatus>
    suspend fun joinNetwork(): AppResult<Unit>
    suspend fun publishEndpoint(endpoint: Endpoint): AppResult<Unit>
    suspend fun resolveEndpoints(identityHash: ByteArray): AppResult<List<Endpoint>>
    suspend fun getPublishedEndpoint(): String?
}
