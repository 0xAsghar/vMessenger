package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.database.dao.LocationSampleDao
import ir.vmessenger.core.database.dao.LocationShareDao
import ir.vmessenger.core.database.entity.LocationSampleEntity
import ir.vmessenger.core.database.entity.LocationShareEntity
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.proto.app.v1.LocationPacket
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.domain.repository.LocationAccessRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.yield

class FakeLocationShareDao : LocationShareDao {
    val shares = mutableListOf<LocationShareEntity>()

    /** Contacts passed to [deleteByContact]; the cascade onto samples is Room's job, not emulated here. */
    val deletedContacts = mutableListOf<String>()

    override suspend fun upsert(entity: LocationShareEntity) {
        shares.removeAll { it.shareId == entity.shareId }
        shares += entity
    }

    override fun observeActive(): Flow<List<LocationShareEntity>> = flowOf(shares.filter { it.active })

    override suspend fun getById(shareId: String): LocationShareEntity? = shares.firstOrNull { it.shareId == shareId }

    override suspend fun getActiveByContact(contactId: String): LocationShareEntity? =
        shares.firstOrNull { it.contactId == contactId && it.active }

    override suspend fun getActiveByContactAndDirection(
        contactId: String,
        direction: MessageDirection,
    ): LocationShareEntity? = shares.firstOrNull { it.contactId == contactId && it.direction == direction && it.active }

    override suspend fun update(entity: LocationShareEntity) {
        shares.replaceAll { if (it.shareId == entity.shareId) entity else it }
    }

    override suspend fun deleteByContact(contactId: String) {
        deletedContacts += contactId
        shares.removeAll { it.contactId == contactId }
    }

    override suspend fun allShareIds(): List<String> = shares.map { it.shareId }

    override suspend fun deleteEndedBefore(ts: Long) {
        shares.removeAll { share -> !share.active && share.endedAtUnixMs?.let { it < ts } == true }
    }

    fun activeIds(contactId: String, direction: MessageDirection): List<String> =
        shares.filter { it.contactId == contactId && it.direction == direction && it.active }.map { it.shareId }
}

/** In-memory samples; [insert] yields so a concurrent mutation of the coordinator's state can interleave. */
class FakeLocationSampleDao : LocationSampleDao {
    val samples = mutableListOf<LocationSampleEntity>()
    private var nextId = 1L

    override suspend fun insert(sample: LocationSampleEntity) {
        yield()
        samples += sample.copy(id = nextId++)
    }

    override fun observeLatest(shareId: String): Flow<LocationSampleEntity?> = flowOf(getLatestSync(shareId))

    override suspend fun getLatest(shareId: String): LocationSampleEntity? = getLatestSync(shareId)

    override fun observeLatestPerShare(): Flow<List<LocationSampleEntity>> =
        flowOf(samples.groupBy { it.shareId }.values.map { group -> group.maxBy { it.id } })

    override suspend fun purgeOlderThan(cutoff: Long) {
        samples.removeAll { it.sampledAtUnixMs < cutoff }
    }

    override suspend fun deleteExcessForShare(shareId: String, keep: Int) {
        val kept = forShare(shareId)
            .sortedWith(compareByDescending<LocationSampleEntity> { it.sampledAtUnixMs }.thenByDescending { it.id })
            .take(keep)
            .map { it.id }
            .toSet()
        samples.removeAll { it.shareId == shareId && it.id !in kept }
    }

    fun forShare(shareId: String): List<LocationSampleEntity> = samples.filter { it.shareId == shareId }

    private fun getLatestSync(shareId: String): LocationSampleEntity? =
        forShare(shareId).maxByOrNull { it.sampledAtUnixMs }
}

class FakeLocationAccessRepository : LocationAccessRepository {
    val granted = mutableListOf<String>()

    override fun observeAll(): Flow<Map<String, Boolean>> = flowOf(granted.associateWith { true })

    override suspend fun setAccess(contactId: String, granted: Boolean) {
        if (granted) this.granted += contactId else this.granted -= contactId
    }

    override suspend fun grantedContactIds(): List<String> = granted.toList()
}

class FakeLocationServiceControl : LocationServiceControl {
    var starts = 0
    var stops = 0

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }
}

object LocationFixtures {
    fun locationEnvelope(
        shareId: String,
        latitude: Double = 35.7,
        longitude: Double = 51.4,
        isFinal: Boolean = false,
        sampledAtUnixMs: Long = System.currentTimeMillis(),
    ): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("loc-$shareId-$sampledAtUnixMs"))
        .setSentAtUnixMs(sampledAtUnixMs)
        .setCounter(1)
        .setLocation(
            LocationPacket.newBuilder()
                .setShareId(ByteString.copyFromUtf8(shareId))
                .setLatitude(latitude)
                .setLongitude(longitude)
                .setAccuracyM(10f)
                .setIsFinal(isFinal)
                .setSampledAtUnixMs(sampledAtUnixMs),
        )
        .build()
}
