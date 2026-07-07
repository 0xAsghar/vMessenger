package ir.vmessenger.domain.repository

import kotlinx.coroutines.flow.Flow

interface LocationAccessRepository {
    fun observeAll(): Flow<Map<String, Boolean>>
    suspend fun setAccess(contactId: String, granted: Boolean)
    suspend fun grantedContactIds(): List<String>
}
