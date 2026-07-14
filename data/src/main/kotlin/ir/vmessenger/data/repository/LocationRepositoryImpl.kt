package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.LocationSampleDao
import ir.vmessenger.core.database.dao.LocationShareDao
import ir.vmessenger.core.database.entity.LocationSampleEntity
import ir.vmessenger.core.database.entity.LocationShareEntity
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.domain.model.LocationSample
import ir.vmessenger.domain.repository.ActiveLocationShare
import ir.vmessenger.domain.repository.LocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepositoryImpl @Inject constructor(
    private val locationShareDao: LocationShareDao,
    private val locationSampleDao: LocationSampleDao,
    private val contactDao: ContactDao,
) : LocationRepository {
    override fun observeActiveShares(): Flow<List<String>> =
        locationShareDao.observeActive().map { shares -> shares.map { it.shareId } }

    override fun observeActiveShareDetails(): Flow<List<ActiveLocationShare>> =
        locationShareDao.observeActive().map { shares ->
            shares.map { share ->
                ActiveLocationShare(
                    shareId = share.shareId,
                    contactId = share.contactId,
                    contactName = share.contactId,
                    outgoing = share.direction == MessageDirection.OUTGOING,
                )
            }
        }

    override fun observeLatestSample(shareId: String): Flow<LocationSample?> =
        locationSampleDao.observeLatest(shareId).map { entity -> entity?.toDomain() }

    override fun observeIsSharing(): Flow<Boolean> =
        locationShareDao.observeActive().map { shares ->
            shares.any { it.direction == MessageDirection.OUTGOING }
        }

    override fun observeIncomingLocations(): Flow<Map<String, LocationSample>> =
        combine(
            locationShareDao.observeActive(),
            locationSampleDao.observeLatestPerShare(),
        ) { shares, latestSamples ->
            val samplesByShare = latestSamples.associateBy { it.shareId }
            shares
                .filter { it.direction == MessageDirection.INCOMING }
                .mapNotNull { share ->
                    val sample = samplesByShare[share.shareId] ?: return@mapNotNull null
                    if (sample.sampledAtUnixMs <= 0L) return@mapNotNull null
                    share.contactId to sample.toDomain()
                }
                .toMap()
        }

    /**
     * Latest position per visible share. Combines the share table with a
     * sample-table-reactive query so the map refreshes on every new sample —
     * previously this only re-emitted when shares started/stopped, so the map
     * stayed empty even while positions were being recorded.
     *
     * Visibility: own outgoing position is always shown; a contact's incoming
     * position is shown only while we also share to them (mutual visibility).
     */
    override fun observeLatestSamples(): Flow<Map<String, LocationSample>> =
        combine(
            locationShareDao.observeActive(),
            locationSampleDao.observeLatestPerShare(),
        ) { shares, latestSamples ->
            val samplesByShare = latestSamples.associateBy { it.shareId }
            val outgoingContacts = shares
                .filter { it.direction == MessageDirection.OUTGOING }
                .map { it.contactId }
                .toSet()
            shares
                .filter { share ->
                    share.direction == MessageDirection.OUTGOING ||
                        share.contactId in outgoingContacts
                }
                .mapNotNull { share ->
                    val sample = samplesByShare[share.shareId] ?: return@mapNotNull null
                    if (sample.sampledAtUnixMs <= 0L) return@mapNotNull null
                    share.shareId to sample.toDomain()
                }
                .toMap()
        }

    override suspend fun startSharing(contactId: String): AppResult<String> {
        val existing = locationShareDao.getActiveByContactAndDirection(contactId, MessageDirection.OUTGOING)
        if (existing != null) return AppResult.Success(existing.shareId)
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

    override suspend fun startIncomingShare(contactId: String, shareId: String) {
        val now = System.currentTimeMillis()
        locationShareDao.upsert(
            LocationShareEntity(
                shareId = shareId,
                contactId = contactId,
                direction = MessageDirection.INCOMING,
                active = true,
                startedAtUnixMs = now,
                endedAtUnixMs = null,
            ),
        )
    }

    override suspend fun stopIncomingShare(shareId: String) {
        stopSharing(shareId)
    }

    override suspend fun recordSample(
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

    override suspend fun getActiveOutgoingShareIds(): List<String> =
        locationShareDao.observeActive().first()
            .filter { it.direction == MessageDirection.OUTGOING }
            .map { it.shareId }

    private fun LocationSampleEntity.toDomain() = LocationSample(
        shareId = shareId,
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        sampledAtUnixMs = sampledAtUnixMs,
    )
}
