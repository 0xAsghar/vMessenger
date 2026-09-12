package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.entity.ContactRequestEntity
import ir.vmessenger.core.database.entity.ContactRequestStatus
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.EndpointCacheEntity
import ir.vmessenger.core.database.entity.LocationAccessEntity
import ir.vmessenger.core.database.entity.LocationSampleEntity
import ir.vmessenger.core.database.entity.LocationShareEntity
import ir.vmessenger.core.database.entity.MailboxBlobEntity
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.core.notifications.ActiveConversationTracker
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactCleanupCoordinatorTest {
    private val peerA = InboundFixtures.peer(0x0A)
    private val peerB = InboundFixtures.peer(0x0B)

    private lateinit var harness: CleanupHarness

    @Before
    fun setUp() {
        harness = CleanupHarness()
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB)
        ActiveConversationTracker.activeConversationId = null
    }

    @After
    fun tearDown() {
        ActiveConversationTracker.activeConversationId = null
    }

    @Test
    fun deleteRemovesAllDerivedState() = runTest {
        seedDerivedState()
        ActiveConversationTracker.activeConversationId = "conv-a"

        harness.coordinator.deleteContact("a")

        assertTrue("sessions closed before and after the revoke", harness.sessionCloser.closed.all { it == "a" })
        assertEquals(2, harness.sessionCloser.closed.size)
        val revoke = harness.messaging.sent.single()
        assertEquals("a", revoke.first)
        assertEquals(ContactResponseType.CONTACT_RESPONSE_REVOKE, revoke.second.contactResponse.type)
        // The id of *their* request to us: what the peer validates a response against.
        val selfHash = harness.identityRepository.identity!!.identityHash
        assertEquals(
            ContactRequestService.deterministicRequestId(peerA.identityHash, selfHash),
            revoke.second.contactResponse.requestId.toStringUtf8(),
        )
        assertEquals(listOf("/data/attachments/in/a-photo.jpg"), harness.attachmentStore.deleted)
        assertEquals(listOf("m-b"), harness.outboxDao.items.map { it.messageId })
        assertEquals(listOf("a"), harness.shareDao.deletedContacts)
        assertTrue(harness.shareDao.shares.none { it.contactId == "a" })
        assertEquals(listOf("b"), harness.locationAccessDao.rows.map { it.contactId })
        assertEquals(1, harness.endpointCacheDao.entries.size)
        assertTrue(harness.endpointCacheDao.entries.single().identityHash.contentEquals(routing(peerB.identityHash)))
        assertEquals(listOf("blob-b"), harness.mailboxDao.blobs.map { it.blobId })
        assertEquals(listOf("req-b"), harness.contactRequestDao.requests.map { it.requestId })
        assertNull(harness.contactDao.getById("a"))
        assertNull(ActiveConversationTracker.activeConversationId)
    }

    @Test
    fun deleteKeepsOtherContactsActiveConversation() = runTest {
        seedDerivedState()
        ActiveConversationTracker.activeConversationId = "conv-b"

        harness.coordinator.deleteContact("a")

        assertEquals("conv-b", ActiveConversationTracker.activeConversationId)
    }

    @Test
    fun deleteOfBlockedContactSendsNoRevoke() = runTest {
        harness.contactDao.contacts.replaceAll { if (it.id == "a") it.copy(blocked = true) else it }

        harness.coordinator.deleteContact("a")

        assertTrue(harness.messaging.sent.isEmpty())
        assertNull(harness.contactDao.getById("a"))
    }

    @Test
    fun deleteUnknownContactIsNoOp() = runTest {
        harness.coordinator.deleteContact("missing")

        assertTrue(harness.sessionCloser.closed.isEmpty())
        assertTrue(harness.messaging.sent.isEmpty())
        assertEquals(2, harness.contactDao.contacts.size)
    }

    @Test
    fun blockClosesSessionsAndStopsSharingButKeepsOutbox() = runTest {
        seedDerivedState()
        harness.locationAccessRepository.granted += "a"
        harness.locationSharing.startSharingToGrantedContacts()
        harness.messaging.sent.clear()

        harness.coordinator.onBlocked("a")

        assertEquals(listOf("a"), harness.sessionCloser.closed)
        assertTrue(harness.shareDao.shares.none { it.contactId == "a" && it.active })
        assertTrue("no revoke, no stop notice", harness.messaging.sent.isEmpty())
        assertEquals(listOf("m-a", "m-b"), harness.outboxDao.items.map { it.messageId })
        assertTrue(harness.contactDao.getById("a") != null)
    }

    private suspend fun seedDerivedState() {
        harness.conversationDao.upsert(conversation("conv-a", "a"))
        harness.conversationDao.upsert(conversation("conv-b", "b"))
        harness.messageDao.insert(message("m-a", "conv-a", attachmentPath = "/data/attachments/in/a-photo.jpg"))
        harness.messageDao.insert(message("m-a2", "conv-a", attachmentPath = null))
        harness.messageDao.insert(message("m-b", "conv-b", attachmentPath = "/data/attachments/in/b-photo.jpg"))
        harness.outboxDao.enqueue(outbox("m-a", "conv-a"))
        harness.outboxDao.enqueue(outbox("m-b", "conv-b"))
        harness.shareDao.upsert(share("out-a", "a", MessageDirection.OUTGOING))
        harness.shareDao.upsert(share("in-a", "a", MessageDirection.INCOMING))
        harness.shareDao.upsert(share("in-b", "b", MessageDirection.INCOMING))
        harness.sampleDao.insert(sample("in-a"))
        harness.locationAccessDao.upsert(LocationAccessEntity("a", canSeeMyLocation = true, updatedAtUnixMs = 1L))
        harness.locationAccessDao.upsert(LocationAccessEntity("b", canSeeMyLocation = true, updatedAtUnixMs = 1L))
        harness.endpointCacheDao.upsert(endpoint(routing(peerA.identityHash)))
        harness.endpointCacheDao.upsert(endpoint(routing(peerB.identityHash)))
        harness.mailboxDao.upsert(blob("blob-a", peerA.identityHash))
        harness.mailboxDao.upsert(blob("blob-b", peerB.identityHash))
        harness.contactRequestDao.upsert(request("req-a", peerA.identityHash))
        harness.contactRequestDao.upsert(request("req-b", peerB.identityHash))
    }

    private fun routing(hash: ByteArray) = IdentityHashMatcher.routingHash(hash)

    private fun conversation(id: String, contactId: String) = ConversationEntity(
        id = id,
        contactId = contactId,
        lastMessageId = null,
        lastActivityUnixMs = 1L,
        unreadCount = 0,
        muted = false,
    )

    private fun message(id: String, conversationId: String, attachmentPath: String?) = MessageEntity(
        messageId = id,
        conversationId = conversationId,
        direction = MessageDirection.OUTGOING,
        contentType = if (attachmentPath == null) MessageContentType.TEXT else MessageContentType.IMAGE,
        body = null,
        replyToMessageId = null,
        status = DeliveryStatus.SENT,
        createdAtUnixMs = 1L,
        sentAtUnixMs = 1L,
        deliveredAtUnixMs = null,
        readAtUnixMs = null,
        attachmentPath = attachmentPath,
    )

    private fun outbox(messageId: String, conversationId: String) = OutboxEntity(
        messageId = messageId,
        conversationId = conversationId,
        sealedPayload = null,
        attemptCount = 0,
        nextAttemptUnixMs = 0L,
        lastError = null,
    )

    private fun share(shareId: String, contactId: String, direction: MessageDirection) = LocationShareEntity(
        shareId = shareId,
        contactId = contactId,
        direction = direction,
        active = true,
        startedAtUnixMs = 1L,
        endedAtUnixMs = null,
    )

    private fun sample(shareId: String) = LocationSampleEntity(
        shareId = shareId,
        latitude = 1.0,
        longitude = 2.0,
        accuracyM = 3f,
        speedMps = null,
        headingDeg = null,
        batteryPct = null,
        sampledAtUnixMs = 1L,
    )

    private fun endpoint(hash: ByteArray) = EndpointCacheEntity(
        identityHash = hash,
        endpointsProto = ByteArray(0),
        sequence = 1L,
        fetchedAtUnixMs = 1L,
        expiresAtUnixMs = Long.MAX_VALUE,
    )

    private fun blob(id: String, recipient: ByteArray) = MailboxBlobEntity(
        blobId = id,
        recipientIdentityHash = recipient,
        sealedPayload = ByteArray(1),
        expiresAtUnixMs = Long.MAX_VALUE,
        createdAtUnixMs = 1L,
    )

    private fun request(id: String, requester: ByteArray) = ContactRequestEntity(
        requestId = id,
        requesterIdentityHash = requester,
        requesterUserHash = "vm2-test",
        requesterDisplayName = id,
        requesterEd25519Public = ByteArray(32),
        requesterX25519StaticPublic = null,
        receivedAtUnixMs = 1L,
        status = ContactRequestStatus.PENDING,
    )
}
