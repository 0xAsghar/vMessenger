package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.pairing.PairingDescriptorCodec
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.proto.wire.v1.PairingDescriptor
import ir.vmessenger.data.network.ContactCleanupCoordinator
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.core.database.entity.ContactRelationshipStatus as EntityRelationshipStatus
import ir.vmessenger.domain.model.ContactRelationshipStatus as DomainRelationshipStatus

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao,
    private val pairingDescriptorCodec: PairingDescriptorCodec,
    private val cleanupCoordinator: ContactCleanupCoordinator,
) : ContactRepository {

    override fun observeContacts(): Flow<List<Contact>> =
        contactDao.observeContacts().map { entities -> entities.map { it.toDomain() } }

    override fun observeBlockedContacts(): Flow<List<Contact>> =
        contactDao.observeBlocked().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getContact(id: String): Contact? =
        contactDao.getById(id)?.toDomain()

    override suspend fun getContactByIdentityHash(identityHash: ByteArray): Contact? =
        contactDao.getByIdentityHash(identityHash)?.toDomain()

    /**
     * Brings a contact we already have back into play.
     *
     * Re-adding someone who rejected us — or who deleted us, which lands as REJECTED — has to move
     * them out of that state, or the add silently does nothing: the retry worker only owes a
     * request to PENDING_OUT, and nothing else would ever ask again.
     */
    private suspend fun reinstate(existing: ContactEntity, status: EntityRelationshipStatus): ContactEntity {
        if (existing.relationshipStatus != EntityRelationshipStatus.REJECTED) return existing
        val revived = existing.copy(relationshipStatus = status, lastSeenUnixMs = null)
        contactDao.update(revived)
        AppLogger.info("Contact", "re-added a previously rejected contact id=${existing.id} as $status")
        return revived
    }

    override suspend fun addContactByDescriptor(
        descriptorBytes: ByteArray,
        alias: String?,
    ): AppResult<Contact> = runCatching {
        val descriptor = PairingDescriptor.parseFrom(descriptorBytes)
        check(pairingDescriptorCodec.verify(descriptor)) { "امضای QR نامعتبر است" }
        val identityPub = descriptor.identityPub.toByteArray()
        val identityHash = UserHashEncoder.identityHashFromPublicKey(identityPub)
        // Prefix-tolerant, because a contact added by hash holds a 16-byte prefix until its first
        // handshake and the exact lookup would miss it — producing a second row for one person.
        contactDao.findByIdentityHash(identityHash)?.let {
            return@runCatching reinstate(it, EntityRelationshipStatus.APPROVED).toDomain()
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
            // Exact byte equality used to be the test here, and it could never match a contact we
            // had already handshaked with: that row holds the *full* 32-byte hash while this one is
            // a zero-padded 16-byte prefix. So re-adding someone produced a second row, inbound
            // traffic still resolved to the first, their acceptance failed its "was this awaited"
            // check, and the new row sat in PENDING_OUT forever. A rejected contact was
            // unrecoverable without deleting them first.
            contactDao.findByIdentityHash(identityHash)?.let {
                return@runCatching reinstate(it, EntityRelationshipStatus.PENDING_OUT).toDomain()
            }
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
        contactDao.findByIdentityHash(identityHash)?.let { existing ->
            val updated = approveExisting(existing, identityHash, ed25519Public, x25519StaticPublic, displayName)
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

    /**
     * Approves a contact we already hold. The request only proves *who* asked, so
     * it may fill in what we do not know yet (placeholder identity key, unpinned
     * static key, the default hash-as-name) but never replaces a pinned key or an
     * alias the user typed, and the shown user hash is re-derived from the
     * authenticated identity rather than taken from the payload.
     */
    private fun approveExisting(
        existing: ContactEntity,
        identityHash: ByteArray,
        ed25519Public: ByteArray,
        x25519StaticPublic: ByteArray?,
        displayName: String,
    ): ContactEntity {
        val learnIdentity = IdentityHashMatcher.isPlaceholderPublicKey(existing.ed25519Public) &&
            !IdentityHashMatcher.isPlaceholderPublicKey(ed25519Public)
        val learnStatic = !existing.hasPinnedStaticKey() &&
            x25519StaticPublic != null &&
            !IdentityHashMatcher.isPlaceholderPublicKey(x25519StaticPublic)
        val newUserHash = UserHashEncoder.encode(if (learnIdentity) identityHash else existing.identityHash)
        // Only replace default names (the raw hash used at add time); never a user alias.
        val hasCustomAlias = existing.displayName.isNotBlank() &&
            existing.displayName != existing.userHash &&
            existing.displayName != newUserHash
        val newDisplayName = when {
            hasCustomAlias -> existing.displayName
            displayName.isNotBlank() -> displayName
            else -> newUserHash
        }
        return existing.copy(
            identityHash = if (learnIdentity) identityHash else existing.identityHash,
            ed25519Public = if (learnIdentity) ed25519Public else existing.ed25519Public,
            x25519StaticPublic = if (learnStatic) x25519StaticPublic else existing.x25519StaticPublic,
            userHash = newUserHash,
            displayName = newDisplayName,
            relationshipStatus = EntityRelationshipStatus.APPROVED,
        )
    }

    override suspend fun updateRelationshipStatus(id: String, status: DomainRelationshipStatus) {
        val contact = contactDao.getById(id) ?: return
        contactDao.update(contact.copy(relationshipStatus = status.toEntity()))
    }

    override suspend fun updateContactAlias(id: String, alias: String) {
        val contact = contactDao.getById(id) ?: return
        contactDao.update(contact.copy(displayName = alias))
    }

    /**
     * Blocking also tears down live sessions and stops location sharing with the
     * contact; queued outbox rows stay (the dispatcher skips blocked contacts).
     */
    override suspend fun blockContact(id: String, blocked: Boolean) {
        val contact = contactDao.getById(id) ?: return
        contactDao.update(contact.copy(blocked = blocked))
        if (blocked) cleanupCoordinator.onBlocked(id)
    }

    /** Full cleanup contract; see [ContactCleanupCoordinator]. */
    override suspend fun deleteContact(id: String) = cleanupCoordinator.deleteContact(id)

    override suspend fun setVerified(id: String, verified: Boolean): AppResult<Unit> {
        val contact = contactDao.getById(id) ?: return AppResult.Error(AppError.ContactNotFound)
        contactDao.update(contact.copy(verified = verified))
        return AppResult.Success(Unit)
    }

    override suspend fun acceptKeyChange(id: String): AppResult<Unit> {
        val contact = contactDao.getById(id)
        val pending = contact?.pendingX25519StaticPublic
        if (contact == null || pending == null) {
            val message = if (contact == null) "مخاطب یافت نشد" else "تغییر کلیدی برای این مخاطب در انتظار نیست"
            return AppResult.Error(AppError.NotFound(message))
        }
        contactDao.update(
            contact.copy(
                x25519StaticPublic = pending,
                pendingX25519StaticPublic = null,
                keyChangedAtUnixMs = null,
                verified = false,
            ),
        )
        AppLogger.info("Contact", "accepted key change contact=$id")
        return AppResult.Success(Unit)
    }

    /**
     * One-off pass (run from `NetworkCoordinator.start`) that re-encodes every contact's user hash in the
     * current canonical form (`vm2-`), so contacts added under 0.x keep a valid, decodable string after an
     * in-place dev upgrade. Hash-only contacts carry a 16-byte prefix padded with zeros, which the encoder
     * accepts. Returns the number of rows rewritten.
     */
    suspend fun normalizeUserHashes(): Int {
        var updated = 0
        contactDao.getAll().forEach { contact ->
            val canonical = UserHashEncoder.encode(contact.identityHash)
            if (canonical != contact.userHash) {
                contactDao.update(contact.copy(userHash = canonical))
                updated++
            }
        }
        if (updated > 0) AppLogger.info("Contact", "normalized $updated contact user hash(es) to vm2")
        return updated
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
        pendingX25519StaticPublicKey = pendingX25519StaticPublic,
        keyChangedAtUnixMs = keyChangedAtUnixMs,
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
