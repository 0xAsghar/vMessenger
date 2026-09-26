package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.LocationSampleDao
import ir.vmessenger.core.database.dao.LocationShareDao
import ir.vmessenger.core.database.entity.ActivityKind
import ir.vmessenger.core.database.entity.LocationSampleEntity
import ir.vmessenger.core.database.entity.LocationShareEntity
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.data.activity.ActivityLogger
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
    private val activityLogger: ActivityLogger,
) : LocationRepository {
    override fun observeActiveShares(): Flow<List<String>> =
        locationShareDao.observeActive().map { shares -> shares.map { it.shareId } }

    /** [ActiveLocationShare.contactName] is shown to the user, so it resolves the display name. */
    override fun observeActiveShareDetails(): Flow<List<ActiveLocationShare>> =
        locationShareDao.observeActive().map { shares ->
            shares.map { share ->
                ActiveLocationShare(
                    shareId = share.shareId,
                    contactId = share.contactId,
                    // Previously the raw contact UUID, which is meaningless on screen.
                    contactName = contactDao.getById(share.contactId)?.displayName ?: share.contactId,
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
        // Only that a share started. Naming the contact would make the log a record of who the
        // user tells where they are, which is precisely what it must not become.
        activityLogger.record(ActivityKind.LocationSharingStarted)
        return AppResult.Success(shareId)
    }

    override suspend fun stopSharing(shareId: String): AppResult<Unit> {
        val share = locationShareDao.getById(shareId) ?: return AppResult.Success(Unit)
        locationShareDao.update(
            share.copy(active = false, endedAtUnixMs = System.currentTimeMillis()),
        )
        activityLogger.record(ActivityKind.LocationSharingStopped)
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

    override suspend fun sharedPath(contactId: String): List<LocationSample> {
        val share = locationShareDao.latestByContactAndDirection(contactId, MessageDirection.INCOMING)
            ?: return emptyList()
        return locationSampleDao.samplesForShare(share.shareId).map { it.toDomain() }
    }

    override fun observeSharedHistory(contactId: String, limit: Int): Flow<List<LocationSample>> =
        locationSampleDao.observeForContact(contactId, MessageDirection.INCOMING, limit)
            .map { rows -> rows.map { it.toDomain() } }

    private fun LocationSampleEntity.toDomain() = LocationSample(
        shareId = shareId,
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        sampledAtUnixMs = sampledAtUnixMs,
    )
}
