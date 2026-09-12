package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.MailboxBlobEntity
import ir.vmessenger.data.repository.FakeContactDao
import ir.vmessenger.data.repository.FakeIdentityRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every mailbox verb is authorised against the authenticated session peer:
 * a peer can only list, fetch and delete blobs addressed to itself, and only
 * approved contacts may park blobs here (content-addressed, quota-limited).
 */
class MailboxProtocolServiceTest {
    private val cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))

    /** B is an approved contact acting as a sender; C is the recipient the blobs are addressed to. */
    private val peerB = InboundFixtures.peer(0x0B)
    private val peerC = InboundFixtures.peer(0x0C)

    private lateinit var mailboxDao: FakeMailboxDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var mailboxSeal: MailboxSeal
    private lateinit var service: MailboxProtocolService
    private var flagBefore = false

    @Before
    fun setUp() {
        flagBefore = P2PConfig.storeAndForwardEnabled
        P2PConfig.storeAndForwardEnabled = true
        mailboxDao = FakeMailboxDao()
        contactDao = FakeContactDao()
        mailboxSeal = MailboxSeal(cryptoEngine)
        val identityRepository = FakeIdentityRepository(cryptoEngine)
        identityRepository.identity = InboundFixtures.identity(0x01)
        val mailboxService = MailboxService(mailboxDao, identityRepository, mailboxSeal)
        service = MailboxProtocolService(mailboxDao, mailboxService, contactDao)
    }

    @After
    fun tearDown() {
        P2PConfig.storeAndForwardEnabled = flagBefore
    }

    @Test
    fun listForOtherRecipientEmpty() = runTest {
        storedForC("blob-1")

        val asB = service.process(MailboxFixtures.list(peerC.identityHash), peerB)
        val asC = service.process(MailboxFixtures.list(peerC.identityHash), peerC)

        assertEquals(0, asB!!.mailboxListResponse.blobIdsCount)
        assertEquals(listOf("blob-1"), asC!!.mailboxListResponse.blobIdsList.map { it.toStringUtf8() })
    }

    @Test
    fun fetchOtherRecipientDenied() = runTest {
        storedForC("blob-1")

        assertNull(service.process(MailboxFixtures.fetch("blob-1"), peerB))
        val owner = service.process(MailboxFixtures.fetch("blob-1"), peerC)
        assertNotNull(owner)
        assertEquals("blob-1", owner!!.mailboxFetchResponse.blob.blobId.toStringUtf8())
        assertEquals(MailboxSeal.SEAL_VERSION, owner.mailboxFetchResponse.blob.sealVersion)
    }

    @Test
    fun deleteOtherRecipientDenied() = runTest {
        storedForC("blob-1")

        val denied = service.process(MailboxFixtures.delete("blob-1"), peerB)
        assertFalse(denied!!.mailboxDeleteAck.accepted)
        assertNotNull(mailboxDao.getById("blob-1"))

        val accepted = service.process(MailboxFixtures.delete("blob-1"), peerC)
        assertTrue(accepted!!.mailboxDeleteAck.accepted)
        assertNull(mailboxDao.getById("blob-1"))
    }

    @Test
    fun unauthenticatedRequestsIgnored() = runTest {
        storedForC("blob-1")

        assertNull(service.process(MailboxFixtures.list(peerC.identityHash), null))
        assertNull(service.process(MailboxFixtures.fetch("blob-1"), null))
        assertNull(service.process(MailboxFixtures.delete("blob-1"), null))
        assertNotNull(mailboxDao.getById("blob-1"))
    }

    @Test
    fun putFromNonApprovedRejected() = runTest {
        val blob = MailboxFixtures.blob(peerC.identityHash, cryptoEngine.randomBytes(64))

        // Stranger: no contact row at all.
        service.process(MailboxFixtures.put(blob), peerB)
        assertTrue(mailboxDao.blobs.isEmpty())

        contactDao.contacts += InboundFixtures.contact("b", peerB, status = ContactRelationshipStatus.PENDING_OUT)
        service.process(MailboxFixtures.put(blob), peerB)
        assertTrue(mailboxDao.blobs.isEmpty())

        contactDao.contacts.clear()
        contactDao.contacts += InboundFixtures.contact("b", peerB, blocked = true)
        service.process(MailboxFixtures.put(blob), peerB)
        assertTrue(mailboxDao.blobs.isEmpty())

        contactDao.contacts.clear()
        contactDao.contacts += InboundFixtures.contact("b", peerB)
        service.process(MailboxFixtures.put(blob), peerB)
        assertEquals(1, mailboxDao.blobs.size)
        assertTrue(mailboxDao.blobs.single().senderIdentityHash!!.contentEquals(peerB.identityHash))
    }

    @Test
    fun putRateLimited() = runTest {
        contactDao.contacts += InboundFixtures.contact("b", peerB)

        repeat(MailboxService.MAX_PER_SENDER + 5) {
            val blob = MailboxFixtures.blob(peerC.identityHash, cryptoEngine.randomBytes(48))
            service.process(MailboxFixtures.put(blob), peerB)
        }

        assertEquals(MailboxService.MAX_PER_SENDER, mailboxDao.blobs.size)
    }

    @Test
    fun putWithWrongSealVersionRejected() = runTest {
        contactDao.contacts += InboundFixtures.contact("b", peerB)
        val blob = MailboxFixtures.blob(peerC.identityHash, cryptoEngine.randomBytes(48), sealVersion = 1)

        service.process(MailboxFixtures.put(blob), peerB)

        assertTrue(mailboxDao.blobs.isEmpty())
    }

    @Test
    fun blobIdContentAddressed() = runTest {
        contactDao.contacts += InboundFixtures.contact("b", peerB)
        val payload = cryptoEngine.randomBytes(80)
        val expectedId = cryptoEngine.sha256(payload).joinToString("") { "%02x".format(it) }.take(32)

        val first = MailboxFixtures.blob(peerC.identityHash, payload, blobId = "attacker")
        val second = MailboxFixtures.blob(peerC.identityHash, payload, blobId = "other")
        service.process(MailboxFixtures.put(first), peerB)
        service.process(MailboxFixtures.put(second), peerB)

        assertEquals(listOf(expectedId), mailboxDao.blobs.map { it.blobId })
        assertNull(mailboxDao.getById("attacker"))
    }

    private suspend fun storedForC(blobId: String): MailboxBlobEntity {
        val entity = MailboxBlobEntity(
            blobId = blobId,
            recipientIdentityHash = peerC.identityHash,
            sealedPayload = cryptoEngine.randomBytes(32),
            expiresAtUnixMs = System.currentTimeMillis() + 60_000L,
            createdAtUnixMs = System.currentTimeMillis(),
            senderIdentityHash = peerB.identityHash,
        )
        mailboxDao.upsert(entity)
        return entity
    }
}
