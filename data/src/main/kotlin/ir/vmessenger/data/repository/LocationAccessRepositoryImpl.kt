package ir.vmessenger.data.repository

import ir.vmessenger.core.database.dao.LocationAccessDao
import ir.vmessenger.core.database.entity.LocationAccessEntity
import ir.vmessenger.domain.repository.LocationAccessRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationAccessRepositoryImpl @Inject constructor(
    private val locationAccessDao: LocationAccessDao,
) : LocationAccessRepository {
    override fun observeAll(): Flow<Map<String, Boolean>> =
        locationAccessDao.observeAll().map { list ->
            list.associate { it.contactId to it.canSeeMyLocation }
        }

    override suspend fun setAccess(contactId: String, granted: Boolean) {
        locationAccessDao.upsert(
            LocationAccessEntity(
                contactId = contactId,
                canSeeMyLocation = granted,
                updatedAtUnixMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun grantedContactIds(): List<String> =
        locationAccessDao.grantedContactIds()
}
