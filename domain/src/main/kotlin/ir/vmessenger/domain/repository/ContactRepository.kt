package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRelationshipStatus
import ir.vmessenger.domain.model.ContactRequest
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun observeContacts(): Flow<List<Contact>>
    suspend fun getContact(id: String): Contact?
    suspend fun getContactByIdentityHash(identityHash: ByteArray): Contact?
    suspend fun addContactByDescriptor(descriptorBytes: ByteArray, alias: String?): AppResult<Contact>
    suspend fun addContactByUserHash(userHash: String, alias: String?): AppResult<Contact>
    suspend fun addApprovedContact(
        identityHash: ByteArray,
        ed25519Public: ByteArray,
        x25519StaticPublic: ByteArray?,
        userHash: String,
        displayName: String,
    ): AppResult<Contact>
    suspend fun updateRelationshipStatus(id: String, status: ContactRelationshipStatus)
    suspend fun updateContactAlias(id: String, alias: String)
    suspend fun blockContact(id: String, blocked: Boolean)
    suspend fun deleteContact(id: String)
}

interface ContactRequestRepository {
    fun observePendingRequests(): Flow<List<ContactRequest>>
    suspend fun saveRequest(request: ContactRequest)
    suspend fun getRequest(requestId: String): ContactRequest?
    suspend fun acceptRequest(requestId: String): AppResult<Contact>
    suspend fun rejectRequest(requestId: String)

    /** Number of times the user has rejected this request/requester so far. */
    suspend fun rejectCountOf(requestId: String): Int
}
