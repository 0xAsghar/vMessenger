package ir.vmessenger.data.repository

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** TOFU rules of [updateLearnedKeys] and the pin conflict check used by the inbound resolver. */
class ContactDaoExtensionsTest {
    private lateinit var cryptoEngine: CryptoEngine
    private lateinit var contactDao: FakeContactDao
    private lateinit var peerEd25519: ByteArray
    private lateinit var peerHash: ByteArray

    @Before
    fun setUp() {
        cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        contactDao = FakeContactDao()
        peerEd25519 = cryptoEngine.generateEd25519KeyPair().publicKey
        peerHash = UserHashEncoder.identityHashFromPublicKey(peerEd25519)
    }

    @Test
    fun updateLearnedKeysFillsPlaceholderIdentityAndNullStaticKey() = runTest {
        // Hash-added contact: 16-byte hash prefix, all-zero ed25519 placeholder, no static key.
        val prefixHash = ByteArray(32).also { peerHash.copyInto(it, 0, 0, 16) }
        contactDao.contacts += contact(identityHash = prefixHash, ed25519 = ByteArray(32), staticPub = null)
        val learnedStatic = cryptoEngine.generateX25519KeyPair().publicKey

        contactDao.updateLearnedKeys("c1", peerHash, peerEd25519, learnedStatic)

        val updated = contactDao.getById("c1")!!
        assertArrayEquals(peerHash, updated.identityHash)
        assertArrayEquals(peerEd25519, updated.ed25519Public)
        assertArrayEquals(learnedStatic, updated.x25519StaticPublic)
    }

    @Test
    fun updateLearnedKeysReplacesPlaceholderStaticKey() = runTest {
        contactDao.contacts += contact(identityHash = peerHash, ed25519 = peerEd25519, staticPub = ByteArray(32))
        val learnedStatic = cryptoEngine.generateX25519KeyPair().publicKey

        contactDao.updateLearnedKeys("c1", peerHash, peerEd25519, learnedStatic)

        assertArrayEquals(learnedStatic, contactDao.getById("c1")!!.x25519StaticPublic)
    }

    @Test
    fun updateLearnedKeysNeverOverwritesPinnedKeys() = runTest {
        val pinnedStatic = cryptoEngine.generateX25519KeyPair().publicKey
        val original = contact(identityHash = peerHash, ed25519 = peerEd25519, staticPub = pinnedStatic)
        contactDao.contacts += original
        val otherStatic = cryptoEngine.generateX25519KeyPair().publicKey
        val otherEd25519 = cryptoEngine.generateEd25519KeyPair().publicKey

        val otherHash = UserHashEncoder.identityHashFromPublicKey(otherEd25519)
        contactDao.updateLearnedKeys("c1", otherHash, otherEd25519, otherStatic)

        val after = contactDao.getById("c1")!!
        assertArrayEquals(pinnedStatic, after.x25519StaticPublic)
        assertArrayEquals(peerEd25519, after.ed25519Public)
        assertArrayEquals(peerHash, after.identityHash)
        assertTrue(after == original)
    }

    @Test
    fun updateLearnedKeysIgnoresPlaceholderInput() = runTest {
        contactDao.contacts += contact(identityHash = peerHash, ed25519 = peerEd25519, staticPub = null)

        contactDao.updateLearnedKeys("c1", peerHash, peerEd25519, ByteArray(32))

        assertTrue(contactDao.getById("c1")!!.x25519StaticPublic == null)
    }

    @Test
    fun conflictsWithPinnedStaticKeyOnlyForRealPins() {
        val pinnedStatic = cryptoEngine.generateX25519KeyPair().publicKey
        val other = cryptoEngine.generateX25519KeyPair().publicKey
        val pinned = contact(identityHash = peerHash, ed25519 = peerEd25519, staticPub = pinnedStatic)
        assertTrue(pinned.conflictsWithPinnedStaticKey(other))
        assertFalse(pinned.conflictsWithPinnedStaticKey(pinnedStatic))
        assertFalse(pinned.copy(x25519StaticPublic = null).conflictsWithPinnedStaticKey(other))
        assertFalse(pinned.copy(x25519StaticPublic = ByteArray(32)).conflictsWithPinnedStaticKey(other))
    }

    private fun contact(identityHash: ByteArray, ed25519: ByteArray, staticPub: ByteArray?) = ContactEntity(
        id = "c1",
        identityHash = identityHash,
        ed25519Public = ed25519,
        x25519StaticPublic = staticPub,
        userHash = UserHashEncoder.encode(identityHash),
        displayName = "c1",
        verified = false,
        blocked = false,
        relationshipStatus = ContactRelationshipStatus.APPROVED,
        createdAtUnixMs = 0L,
        lastSeenUnixMs = null,
    )
}
