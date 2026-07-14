package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.database.dao.ContactRequestDao
import ir.vmessenger.core.database.entity.ContactRequestEntity
import ir.vmessenger.core.database.entity.ContactRequestStatus
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.ContactRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRequestRepositoryImpl @Inject constructor(
    private val contactRequestDao: ContactRequestDao,
    private val contactRepository: ContactRepository,
) : ContactRequestRepository {

    override fun observePendingRequests(): Flow<List<ContactRequest>> =
        contactRequestDao.observePending().map { list -> list.map { it.toDomain() } }

    override suspend fun saveRequest(request: ContactRequest) {
        // requestId is deterministic per (requester, target), so a resent request
        // reuses the same row — preserve the reject counter across re-saves.
        val existingRejects = contactRequestDao.rejectCountOf(request.requestId) ?: 0
        contactRequestDao.upsert(
            ContactRequestEntity(
                requestId = request.requestId,
                requesterIdentityHash = request.requesterIdentityHash,
                requesterUserHash = request.requesterUserHash,
                requesterDisplayName = request.requesterDisplayName,
                requesterEd25519Public = request.requesterEd25519PublicKey,
                requesterX25519StaticPublic = request.requesterX25519StaticPublicKey,
                receivedAtUnixMs = request.receivedAtUnixMs,
                status = ContactRequestStatus.PENDING,
                rejectCount = existingRejects,
            ),
        )
    }

    override suspend fun rejectCountOf(requestId: String): Int =
        contactRequestDao.rejectCountOf(requestId) ?: 0

    override suspend fun getRequest(requestId: String): ContactRequest? =
        contactRequestDao.getById(requestId)?.toDomain()

    override suspend fun acceptRequest(requestId: String): AppResult<Contact> {
        val entity = contactRequestDao.getById(requestId)
            ?: return AppResult.Error(AppError.NotFound("درخواست یافت نشد"))
        return contactRepository.addApprovedContact(
            identityHash = entity.requesterIdentityHash,
            ed25519Public = entity.requesterEd25519Public,
            x25519StaticPublic = entity.requesterX25519StaticPublic,
            userHash = entity.requesterUserHash,
            displayName = entity.requesterDisplayName,
        ).also { result ->
            if (result is AppResult.Success) {
                contactRequestDao.update(entity.copy(status = ContactRequestStatus.ACCEPTED))
            }
        }
    }

    override suspend fun rejectRequest(requestId: String) {
        val entity = contactRequestDao.getById(requestId) ?: return
        contactRequestDao.update(
            entity.copy(
                status = ContactRequestStatus.REJECTED,
                rejectCount = entity.rejectCount + 1,
            ),
        )
    }

    private fun ContactRequestEntity.toDomain() = ContactRequest(
        requestId = requestId,
        requesterIdentityHash = requesterIdentityHash,
        requesterUserHash = requesterUserHash,
        requesterDisplayName = requesterDisplayName,
        requesterEd25519PublicKey = requesterEd25519Public,
        requesterX25519StaticPublicKey = requesterX25519StaticPublic,
        receivedAtUnixMs = receivedAtUnixMs,
    )
}
