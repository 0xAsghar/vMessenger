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

@Suppress("TooManyFunctions")
interface LocationRepository {
    fun observeActiveShares(): Flow<List<String>>
    fun observeActiveShareDetails(): Flow<List<ActiveLocationShare>>
    fun observeLatestSample(shareId: String): Flow<LocationSample?>
    fun observeLatestSamples(): Flow<Map<String, LocationSample>>

    /** True while this device is actively sharing its location with anyone. */
    fun observeIsSharing(): Flow<Boolean>

    /** Latest position of each contact currently sharing their location with us (by contactId). */
    fun observeIncomingLocations(): Flow<Map<String, LocationSample>>
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

    /**
     * Every position a contact shared with us during their most recent sharing session, oldest
     * first — a read-only replay of what they chose to send while the share was open.
     *
     * Bounded by the same retention that bounds the samples themselves, so it thins out and then
     * empties as a session ages. Nothing is inferred from it: it is the route, not a history of
     * where someone tends to be.
     */
    suspend fun sharedPath(contactId: String): List<LocationSample>

    /**
     * The positions a contact has shared with us, newest first, across their sessions inside the
     * retention window (at most [limit]): their location history, as the contact page shows it.
     */
    fun observeSharedHistory(contactId: String, limit: Int): Flow<List<LocationSample>>
}
