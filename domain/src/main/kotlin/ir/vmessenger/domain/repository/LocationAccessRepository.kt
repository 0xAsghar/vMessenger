package ir.vmessenger.domain.repository

import kotlinx.coroutines.flow.Flow

interface LocationAccessRepository {
    fun observeAll(): Flow<Map<String, Boolean>>
    suspend fun setAccess(contactId: String, granted: Boolean)
    suspend fun grantedContactIds(): List<String>

    /**
     * Whether this one contact is granted, right now.
     *
     * Starting a share awaits a network send per contact, so a list read at the top of that loop
     * is stale by the time the loop reaches its later entries — and a contact un-ticked in the
     * meantime would still have had a share opened for them.
     */
    suspend fun isGranted(contactId: String): Boolean
}
