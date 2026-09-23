package ir.vmessenger.data.repository

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.crypto.pairing.PairingDescriptorCodec
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.IdentityEntity
import ir.vmessenger.core.database.entity.PendingRevokeEntity
import ir.vmessenger.data.activity.testActivityLogger
import ir.vmessenger.data.network.CleanupHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactRepositoryImplTest {
    private lateinit var cryptoEngine: CryptoEngine
    private lateinit var contactDao: FakeContactDao
    private lateinit var identityDao: FakeIdentityDao
    private lateinit var cleanup: CleanupHarness
    private lateinit var repository: ContactRepositoryImpl

    @Before
    fun setUp() {
        cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        contactDao = FakeContactDao()
        identityDao = FakeIdentityDao()
        cleanup = CleanupHarness(contactDao)
        repository = ContactRepositoryImpl(
            contactDao,
            PairingDescriptorCodec(cryptoEngine),
            cleanup.coordinator,
            testActivityLogger(),
            identityDao,
        )
    }

    @Test
    fun aScannedContactWaitsForTheirApproval() = runTest {
        val result = repository.addContactByDescriptor(signedDescriptor(cryptoEngine.generateEd25519KeyPair()), null)

        assertTrue(result is AppResult.Success)
        assertEquals(ContactRelationshipStatus.PENDING_OUT, contactDao.contacts.single().relationshipStatus)
    }

    @Test
    fun scanningOurOwnQrAddsNoOne() = runTest {
        val keys = cryptoEngine.generateEd25519KeyPair()
        identityDao.identity = ownIdentity(keys)

        val result = repository.addContactByDescriptor(signedDescriptor(keys), null)

        assertTrue(result is AppResult.Error && result.error == AppError.OwnIdentity)
        assertTrue(contactDao.contacts.isEmpty())
    }

    @Test
    fun enteringOurOwnUserIdAddsNoOne() = runTest {
        val keys = cryptoEngine.generateEd25519KeyPair()
        val own = ownIdentity(keys)
        identityDao.identity = own

        val result = repository.addContactByUserHash(own.userHash, null)

        assertTrue(result is AppResult.Error && result.error == AppError.OwnIdentity)
        assertTrue(contactDao.contacts.isEmpty())
    }

    @Test
    fun someoneElsesUserIdIsStillAdded() = runTest {
        identityDao.identity = ownIdentity(cryptoEngine.generateEd25519KeyPair())
        val other = UserHashEncoder.identityHashFromPublicKey(cryptoEngine.generateEd25519KeyPair().publicKey)

        val result = repository.addContactByUserHash(UserHashEncoder.encode(other), null)

        assertTrue(result is AppResult.Success)
        assertEquals(1, contactDao.contacts.size)
    }

    @Test
    fun rescanningSomeoneWhoRejectedUsAsksThemAgain() = runTest {
        val keys = cryptoEngine.generateEd25519KeyPair()
        repository.addContactByDescriptor(signedDescriptor(keys), null)
        val id = contactDao.contacts.single().id
        contactDao.update(contactDao.getById(id)!!.copy(relationshipStatus = ContactRelationshipStatus.REJECTED))

        repository.addContactByDescriptor(signedDescriptor(keys), null)

        assertEquals(ContactRelationshipStatus.PENDING_OUT, contactDao.getById(id)!!.relationshipStatus)
    }

    @Test
    fun reAddingSomeoneWeDeletedWithdrawsTheRevokeStillOwedToThem() = runTest {
        val fullHash = UserHashEncoder.identityHashFromPublicKey(cryptoEngine.generateEd25519KeyPair().publicKey)
        val otherHash = UserHashEncoder.identityHashFromPublicKey(cryptoEngine.generateEd25519KeyPair().publicKey)
        cleanup.pendingRevokeDao.upsert(revokeFor(fullHash))
        cleanup.pendingRevokeDao.upsert(revokeFor(otherHash))

        // By user hash, which carries only the 16-byte routing prefix of the hash the revoke was queued under.
        repository.addContactByUserHash(UserHashEncoder.encode(IdentityHashMatcher.routingHash(fullHash)), null)

        assertEquals(1, cleanup.pendingRevokeDao.queued.size)
        assertArrayEquals(otherHash, cleanup.pendingRevokeDao.queued.single().identityHash)
    }

    @Test
    fun approvingARequestFromSomeoneWeDeletedWithdrawsTheRevoke() = runTest {
        val keys = cryptoEngine.generateEd25519KeyPair()
        val fullHash = UserHashEncoder.identityHashFromPublicKey(keys.publicKey)
        cleanup.pendingRevokeDao.upsert(revokeFor(fullHash))

        repository.addApprovedContact(fullHash, keys.publicKey, null, UserHashEncoder.encode(fullHash), "Sara")

        assertTrue(cleanup.pendingRevokeDao.queued.isEmpty())
    }

    @Test
    fun acceptKeyChangeMovesPendingToPinned() = runTest {
        val oldStatic = cryptoEngine.generateX25519KeyPair().publicKey
        val newStatic = cryptoEngine.generateX25519KeyPair().publicKey
        val contact = contact("c1", oldStatic).copy(verified = true)
        contactDao.contacts += contact
        contactDao.recordPendingKeyChange("c1", newStatic, ts = 1_000L)
        assertTrue(repository.getContact("c1")!!.keyChangePending)

        val result = repository.acceptKeyChange("c1")

        assertTrue(result is AppResult.Success)
        val updated = contactDao.getById("c1")!!
        assertArrayEquals(newStatic, updated.x25519StaticPublic)
        assertNull(updated.pendingX25519StaticPublic)
        assertNull(updated.keyChangedAtUnixMs)
        assertFalse("verified must be reset so the user re-checks the safety number", updated.verified)
        val domain = repository.getContact("c1")!!
        assertFalse(domain.keyChangePending)
        assertArrayEquals(newStatic, domain.x25519StaticPublicKey)
    }

    @Test
    fun acceptKeyChangeWithoutPendingKeyFails() = runTest {
        val pinned = cryptoEngine.generateX25519KeyPair().publicKey
        contactDao.contacts += contact("c1", pinned)

        val result = repository.acceptKeyChange("c1")

        assertTrue(result is AppResult.Error && result.error is AppError.NotFound)
        assertArrayEquals(pinned, contactDao.getById("c1")!!.x25519StaticPublic)
    }

    @Test
    fun acceptKeyChangeUnknownContactFails() = runTest {
        val result = repository.acceptKeyChange("missing")
        assertTrue(result is AppResult.Error && result.error is AppError.NotFound)
    }

    @Test
    fun keyChangePendingIsExposedOnDomainContact() = runTest {
        val pinned = cryptoEngine.generateX25519KeyPair().publicKey
        contactDao.contacts += contact("c1", pinned)
        assertFalse(repository.getContact("c1")!!.keyChangePending)

        contactDao.recordPendingKeyChange("c1", cryptoEngine.generateX25519KeyPair().publicKey, ts = 42L)

        val domain = repository.getContact("c1")!!
        assertTrue(domain.keyChangePending)
        assertTrue(domain.keyChangedAtUnixMs == 42L)
        assertArrayEquals("the pin must not move until the user accepts", pinned, domain.x25519StaticPublicKey)
    }

    @Test
    fun addApprovedContactKeepsAliasAndPinnedKeys() = runTest {
        // An already-known peer re-sends a request with a new name and a foreign user hash.
        val pinned = cryptoEngine.generateX25519KeyPair().publicKey
        val existing = contact("c1", pinned).copy(
            displayName = "Mom",
            relationshipStatus = ContactRelationshipStatus.PENDING_OUT,
        )
        contactDao.contacts += existing

        val result = repository.addApprovedContact(
            identityHash = existing.identityHash,
            ed25519Public = existing.ed25519Public,
            x25519StaticPublic = cryptoEngine.generateX25519KeyPair().publicKey,
            userHash = "vm2-attacker-chosen",
            displayName = "Not Mom",
        )

        assertTrue(result is AppResult.Success)
        val updated = contactDao.getById("c1")!!
        assertEquals(ContactRelationshipStatus.APPROVED, updated.relationshipStatus)
        assertEquals("Mom", updated.displayName)
        assertEquals(UserHashEncoder.encode(existing.identityHash), updated.userHash)
        assertArrayEquals("a pinned static key is never replaced here", pinned, updated.x25519StaticPublic)
        assertEquals(1, contactDao.contacts.size)
    }

    @Test
    fun addApprovedContactFillsPlaceholdersOfHashAddedContact() = runTest {
        val ed25519 = cryptoEngine.generateEd25519KeyPair().publicKey
        val fullHash = UserHashEncoder.identityHashFromPublicKey(ed25519)
        val prefix = IdentityHashMatcher.routingHash(fullHash)
        val prefixUserHash = UserHashEncoder.encode(prefix)
        contactDao.contacts += contact("c1", null).copy(
            identityHash = prefix,
            ed25519Public = ByteArray(32),
            userHash = prefixUserHash,
            displayName = prefixUserHash,
            relationshipStatus = ContactRelationshipStatus.PENDING_OUT,
        )
        val static = cryptoEngine.generateX25519KeyPair().publicKey

        val result = repository.addApprovedContact(fullHash, ed25519, static, "ignored", "Sara")

        assertTrue(result is AppResult.Success)
        val updated = contactDao.getById("c1")!!
        assertEquals(1, contactDao.contacts.size)
        assertArrayEquals(fullHash, updated.identityHash)
        assertArrayEquals(ed25519, updated.ed25519Public)
        assertArrayEquals(static, updated.x25519StaticPublic)
        assertEquals(UserHashEncoder.encode(fullHash), updated.userHash)
        assertEquals("Sara", updated.displayName)
    }

    private fun signedDescriptor(keys: KeyPair): ByteArray {
        val hash = UserHashEncoder.identityHashFromPublicKey(keys.publicKey)
        return PairingDescriptorCodec(cryptoEngine)
            .createSigned(keys.publicKey, UserHashEncoder.encode(hash), "Sara", keys.privateKey)
            .toByteArray()
    }

    private fun ownIdentity(keys: KeyPair): IdentityEntity {
        val hash = UserHashEncoder.identityHashFromPublicKey(keys.publicKey)
        return IdentityEntity(
            ed25519Public = keys.publicKey,
            identityHash = hash,
            userHash = UserHashEncoder.encode(hash),
            x25519StaticPublic = cryptoEngine.generateX25519KeyPair().publicKey,
            createdAtUnixMs = 0L,
        )
    }

    private fun revokeFor(identityHash: ByteArray) = PendingRevokeEntity(
        identityHash = identityHash,
        ed25519Public = ByteArray(32) { 1 },
        x25519StaticPublic = null,
        requestId = "cr-test",
        createdAtUnixMs = 0L,
    )

    private fun contact(id: String, staticPub: ByteArray?): ContactEntity {
        val ed25519 = cryptoEngine.generateEd25519KeyPair().publicKey
        val identityHash = UserHashEncoder.identityHashFromPublicKey(ed25519)
        return ContactEntity(
            id = id,
            identityHash = identityHash,
            ed25519Public = ed25519,
            x25519StaticPublic = staticPub,
            userHash = UserHashEncoder.encode(identityHash),
            displayName = id,
            verified = false,
            blocked = false,
            relationshipStatus = ContactRelationshipStatus.APPROVED,
            createdAtUnixMs = 0L,
            lastSeenUnixMs = null,
        )
    }
}
