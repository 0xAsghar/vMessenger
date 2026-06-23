package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.LocationSample
import kotlinx.coroutines.flow.Flow

interface LocationRepository {
    fun observeActiveShares(): Flow<List<String>>
    fun observeLatestSample(shareId: String): Flow<LocationSample?>
    suspend fun startSharing(contactId: String): AppResult<String>
    suspend fun stopSharing(shareId: String): AppResult<Unit>
}
