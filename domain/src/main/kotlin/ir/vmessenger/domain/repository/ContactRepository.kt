package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRelationshipStatus
import ir.vmessenger.domain.model.ContactRequest
import kotlinx.coroutines.flow.Flow

@Suppress("TooManyFunctions") // the contact aggregate's full contract; use cases wrap one method each
interface ContactRepository {
    fun observeContacts(): Flow<List<Contact>>

    /**
     * The blocked contacts, which [observeContacts] deliberately hides. Without this the block
     * action would be one-way: nothing else in the app can name a contact once it is blocked.
     */
    fun observeBlockedContacts(): Flow<List<Contact>>
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

    /**
     * Accepts a contact's changed X25519 static key: the pending key becomes the pinned key, the
     * pending record is cleared and `verified` is reset so the user re-checks the safety number.
     * Fails with [ir.vmessenger.core.common.AppError.NotFound] when there is no such contact or
     * no key change is pending.
     */
    suspend fun acceptKeyChange(id: String): AppResult<Unit>

    /**
     * Records whether the user has compared this contact's safety number out of band.
     * Purely a local annotation: it changes no key and grants no trust by itself, but it is
     * what the UI shows so a later key change is visibly a change from something verified.
     */
    suspend fun setVerified(id: String, verified: Boolean): AppResult<Unit>
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
