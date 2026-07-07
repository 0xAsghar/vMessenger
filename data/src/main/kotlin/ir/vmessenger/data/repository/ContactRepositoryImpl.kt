package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.pairing.PairingDescriptorCodec
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus as EntityRelationshipStatus
import ir.vmessenger.core.proto.wire.v1.PairingDescriptor
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRelationshipStatus as DomainRelationshipStatus
import ir.vmessenger.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao,
    private val pairingDescriptorCodec: PairingDescriptorCodec,
) : ContactRepository {

    override fun observeContacts(): Flow<List<Contact>> =
        contactDao.observeContacts().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getContact(id: String): Contact? =
        contactDao.getById(id)?.toDomain()

    override suspend fun getContactByIdentityHash(identityHash: ByteArray): Contact? =
        contactDao.getByIdentityHash(identityHash)?.toDomain()

    override suspend fun addContactByDescriptor(
        descriptorBytes: ByteArray,
        alias: String?,
    ): AppResult<Contact> = runCatching {
        val descriptor = PairingDescriptor.parseFrom(descriptorBytes)
        check(pairingDescriptorCodec.verify(descriptor)) { "امضای QR نامعتبر است" }
        val identityPub = descriptor.identityPub.toByteArray()
        val identityHash = UserHashEncoder.identityHashFromPublicKey(identityPub)
        contactDao.getByIdentityHash(identityHash)?.let {
            return@runCatching it.toDomain()
        }
        val entity = ContactEntity(
            id = UUID.randomUUID().toString(),
            identityHash = identityHash,
            ed25519Public = identityPub,
            userHash = descriptor.userHash,
            displayName = alias?.takeIf { it.isNotBlank() } ?: descriptor.displayLabel.ifBlank { descriptor.userHash },
            verified = false,
            blocked = false,
            relationshipStatus = EntityRelationshipStatus.APPROVED,
            createdAtUnixMs = System.currentTimeMillis(),
            lastSeenUnixMs = null,
        )
        contactDao.insert(entity)
        entity.toDomain()
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Error(AppError.Validation(it.message ?: "افزودن مخاطب ناموفق بود")) },
    )

    override suspend fun addContactByUserHash(userHash: String, alias: String?): AppResult<Contact> =
        runCatching {
            val partialHash = UserHashEncoder.decode(userHash)
                ?: run {
                    val reason = UserHashEncoder.decodeFailureReason(userHash)
                    AppLogger.warn("Contact", "addByUserHash decode failed reason=$reason")
                    throw IllegalArgumentException("شناسه کاربری نامعتبر است")
                }
            val identityHash = ByteArray(32).also { partialHash.copyInto(it, 0, 0, partialHash.size) }
            contactDao.getByIdentityHash(identityHash)?.let { return@runCatching it.toDomain() }
            val entity = ContactEntity(
                id = UUID.randomUUID().toString(),
                identityHash = identityHash,
                ed25519Public = ByteArray(32),
                userHash = userHash.trim(),
                displayName = alias?.takeIf { it.isNotBlank() } ?: userHash,
                verified = false,
                blocked = false,
                relationshipStatus = EntityRelationshipStatus.PENDING_OUT,
                createdAtUnixMs = System.currentTimeMillis(),
                lastSeenUnixMs = null,
            )
            contactDao.insert(entity)
            entity.toDomain()
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Error(AppError.Validation(it.message ?: "افزودن مخاطب ناموفق بود")) },
        )

    override suspend fun addApprovedContact(
        identityHash: ByteArray,
        ed25519Public: ByteArray,
        x25519StaticPublic: ByteArray?,
        userHash: String,
        displayName: String,
    ): AppResult<Contact> = runCatching {
        contactDao.getByIdentityHash(identityHash)?.let { existing ->
            val updated = existing.copy(
                ed25519Public = ed25519Public,
                x25519StaticPublic = x25519StaticPublic,
                userHash = userHash,
                displayName = displayName.ifBlank { userHash },
                relationshipStatus = EntityRelationshipStatus.APPROVED,
            )
            contactDao.update(updated)
            return@runCatching updated.toDomain()
        }
        val entity = ContactEntity(
            id = UUID.randomUUID().toString(),
            identityHash = identityHash,
            ed25519Public = ed25519Public,
            x25519StaticPublic = x25519StaticPublic,
            userHash = userHash,
            displayName = displayName.ifBlank { userHash },
            verified = false,
            blocked = false,
            relationshipStatus = EntityRelationshipStatus.APPROVED,
            createdAtUnixMs = System.currentTimeMillis(),
            lastSeenUnixMs = null,
        )
        contactDao.insert(entity)
        entity.toDomain()
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Error(AppError.Validation(it.message ?: "افزودن مخاطب ناموفق بود")) },
    )

    override suspend fun updateRelationshipStatus(id: String, status: DomainRelationshipStatus) {
        val contact = contactDao.getById(id) ?: return
        contactDao.update(contact.copy(relationshipStatus = status.toEntity()))
    }

    override suspend fun updateContactAlias(id: String, alias: String) {
        val contact = contactDao.getById(id) ?: return
        contactDao.update(contact.copy(displayName = alias))
    }

    override suspend fun blockContact(id: String, blocked: Boolean) {
        val contact = contactDao.getById(id) ?: return
        contactDao.update(contact.copy(blocked = blocked))
    }

    override suspend fun deleteContact(id: String) {
        contactDao.deleteById(id)
    }

    private fun ContactEntity.toDomain() = Contact(
        id = id,
        identityHash = identityHash,
        ed25519PublicKey = ed25519Public,
        x25519StaticPublicKey = x25519StaticPublic,
        userHash = userHash,
        displayName = displayName,
        verified = verified,
        blocked = blocked,
        relationshipStatus = relationshipStatus.toDomain(),
        createdAtUnixMs = createdAtUnixMs,
        lastSeenUnixMs = lastSeenUnixMs,
    )

    private fun EntityRelationshipStatus.toDomain(): DomainRelationshipStatus = when (this) {
        EntityRelationshipStatus.APPROVED -> DomainRelationshipStatus.APPROVED
        EntityRelationshipStatus.PENDING_OUT -> DomainRelationshipStatus.PENDING_OUT
        EntityRelationshipStatus.PENDING_IN -> DomainRelationshipStatus.PENDING_IN
        EntityRelationshipStatus.REJECTED -> DomainRelationshipStatus.REJECTED
    }

    private fun DomainRelationshipStatus.toEntity(): EntityRelationshipStatus = when (this) {
        DomainRelationshipStatus.APPROVED -> EntityRelationshipStatus.APPROVED
        DomainRelationshipStatus.PENDING_OUT -> EntityRelationshipStatus.PENDING_OUT
        DomainRelationshipStatus.PENDING_IN -> EntityRelationshipStatus.PENDING_IN
        DomainRelationshipStatus.REJECTED -> EntityRelationshipStatus.REJECTED
    }
}
