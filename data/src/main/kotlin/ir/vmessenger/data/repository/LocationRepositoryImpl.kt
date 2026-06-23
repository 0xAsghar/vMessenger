package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.database.dao.LocationSampleDao
import ir.vmessenger.core.database.dao.LocationShareDao
import ir.vmessenger.core.database.entity.LocationSampleEntity
import ir.vmessenger.core.database.entity.LocationShareEntity
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.domain.model.LocationSample
import ir.vmessenger.domain.repository.LocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepositoryImpl @Inject constructor(
    private val locationShareDao: LocationShareDao,
    private val locationSampleDao: LocationSampleDao,
) : LocationRepository {
    override fun observeActiveShares(): Flow<List<String>> =
        locationShareDao.observeActive().map { shares -> shares.map { it.shareId } }

    override fun observeLatestSample(shareId: String): Flow<LocationSample?> =
        locationSampleDao.observeLatest(shareId).map { entity ->
            entity?.toDomain()
        }

    override suspend fun startSharing(contactId: String): AppResult<String> {
        val shareId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        locationShareDao.upsert(
            LocationShareEntity(
                shareId = shareId,
                contactId = contactId,
                direction = MessageDirection.OUTGOING,
                active = true,
                startedAtUnixMs = now,
                endedAtUnixMs = null,
            ),
        )
        return AppResult.Success(shareId)
    }

    override suspend fun stopSharing(shareId: String): AppResult<Unit> {
        val share = locationShareDao.getById(shareId) ?: return AppResult.Success(Unit)
        locationShareDao.update(
            share.copy(active = false, endedAtUnixMs = System.currentTimeMillis()),
        )
        return AppResult.Success(Unit)
    }

    suspend fun recordSample(
        shareId: String,
        latitude: Double,
        longitude: Double,
        accuracyM: Float,
        batteryPct: Int?,
    ) {
        locationSampleDao.insert(
            LocationSampleEntity(
                shareId = shareId,
                latitude = latitude,
                longitude = longitude,
                accuracyM = accuracyM,
                speedMps = null,
                headingDeg = null,
                batteryPct = batteryPct,
                sampledAtUnixMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun LocationSampleEntity.toDomain() = LocationSample(
        shareId = shareId,
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        sampledAtUnixMs = sampledAtUnixMs,
    )
}
