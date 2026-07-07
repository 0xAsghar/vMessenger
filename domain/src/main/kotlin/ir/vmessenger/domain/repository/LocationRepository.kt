package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.LocationSample
import kotlinx.coroutines.flow.Flow

data class ActiveLocationShare(
    val shareId: String,
    val contactId: String,
    val contactName: String,
    val outgoing: Boolean,
)

interface LocationRepository {
    fun observeActiveShares(): Flow<List<String>>
    fun observeActiveShareDetails(): Flow<List<ActiveLocationShare>>
    fun observeLatestSample(shareId: String): Flow<LocationSample?>
    fun observeLatestSamples(): Flow<Map<String, LocationSample>>
    suspend fun startSharing(contactId: String): AppResult<String>
    suspend fun stopSharing(shareId: String): AppResult<Unit>
    suspend fun startIncomingShare(contactId: String, shareId: String)
    suspend fun stopIncomingShare(shareId: String)
    suspend fun recordSample(
        shareId: String,
        latitude: Double,
        longitude: Double,
        accuracyM: Float,
        batteryPct: Int?,
    )
    suspend fun getActiveOutgoingShareIds(): List<String>
}
