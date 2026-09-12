package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.MessageRecipientEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.core.notifications.ActiveConversationTracker
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.ReceiptType
import ir.vmessenger.data.repository.FakeContactDao
import ir.vmessenger.data.repository.FakeConversationDao
import ir.vmessenger.data.repository.FakeMessageDao
import ir.vmessenger.data.repository.FakeMessageRecipientDao
import ir.vmessenger.network.messaging.IncomingEnvelope
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IncomingMessageCollectorTest {
    private val peerA = InboundFixtures.peer(0x0A)
    private val peerB = InboundFixtures.peer(0x0B)

    private lateinit var harness: InboundHarness
    private lateinit var contactDao: FakeContactDao
    private lateinit var conversationDao: FakeConversationDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var recipientDao: FakeMessageRecipientDao
    private lateinit var messaging: FakeMessagingPort
    private lateinit var notifier: FakeIncomingMessageNotifier
    private lateinit var routes: FakeInboundRoutes
    private lateinit var collector: IncomingMessageCollector

    @Before
    fun setUp() {
        harness = InboundHarness()
        contactDao = harness.contactDao
        conversationDao = harness.conversationDao
        messageDao = harness.messageDao
        outboxDao = harness.outboxDao
        recipientDao = harness.recipientDao
        messaging = harness.messaging
        notifier = harness.notifier
        routes = harness.routes
        collector = harness.collector
        ActiveConversationTracker.activeConversationId = null
    }

    @After
    fun tearDown() {
        ActiveConversationTracker.activeConversationId = null
    }

    @Test
    fun approvedChatPersistedNotifiedAndAcked() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)

        deliver("a", InboundFixtures.chatEnvelope("m1", "salam"))

        val stored = messageDao.getById("m1")
        assertNotNull(stored)
        assertEquals(MessageDirection.INCOMING, stored!!.direction)
        assertEquals("salam", stored.body)
        val conversation = conversationDao.getByContactId("a")
        assertNotNull(conversation)
        assertEquals(1, conversation!!.unreadCount)
        assertEquals(1, notifier.shown.size)
        assertEquals(1, messaging.receiptsFor("m1").size)
    }

    @Test
    fun incomingReplyKeepsTheQuotedMessageId() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)

        deliver("a", InboundFixtures.chatEnvelope("m1", "salam", replyToMessageId = "their-m0"))

        assertEquals("their-m0", messageDao.getById("m1")?.replyToMessageId)
    }

    @Test
    fun incomingChatWithoutAQuoteStoresNoReplyId() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)

        deliver("a", InboundFixtures.chatEnvelope("m1", "salam"))

        assertNull(messageDao.getById("m1")?.replyToMessageId)
    }

    @Test
    fun anOversizedQuotedIdIsNotStored() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)

        deliver("a", InboundFixtures.chatEnvelope("m1", "salam", replyToMessageId = "x".repeat(200)))

        assertNull(messageDao.getById("m1")?.replyToMessageId)
    }

    @Test
    fun chatFromBlockedContactDropped() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA, blocked = true)

        deliver("a", InboundFixtures.chatEnvelope("m1"))

        assertNull(messageDao.getById("m1"))
        assertTrue(messaging.sent.isEmpty())
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun chatFromPendingContactDropped() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA, status = ContactRelationshipStatus.PENDING_OUT)

        deliver("a", InboundFixtures.chatEnvelope("m1"))

        assertNull(messageDao.getById("m1"))
        assertTrue(messaging.sent.isEmpty())
    }

    @Test
    fun strangerChatDropped() = runTest {
        deliver(ContactRequestHandler.strangerContactId(peerB.identityHash), InboundFixtures.chatEnvelope("m1"))

        assertNull(messageDao.getById("m1"))
        assertTrue(conversationDao.conversations.isEmpty())
        assertTrue(messaging.sent.isEmpty())
    }

    @Test
    fun networkNodesFromStrangerOrPendingContactDropped() = runTest {
        contactDao.contacts += InboundFixtures.contact("p", peerA, status = ContactRelationshipStatus.PENDING_IN)

        deliver(ContactRequestHandler.strangerContactId(peerB.identityHash), InboundFixtures.networkNodesEnvelope())
        deliver("p", InboundFixtures.networkNodesEnvelope())

        assertTrue(routes.infrastructure.isEmpty())
    }

    @Test
    fun networkNodesFromApprovedContactRouted() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)

        deliver("a", InboundFixtures.networkNodesEnvelope())

        assertEquals(1, routes.infrastructure.size)
        assertTrue(routes.infrastructure.single().hasNetworkNodes())
    }

    @Test
    fun receiptForIncomingMessageIgnored() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)
        conversationDao.conversations += conversation("conv-a", "a")
        messageDao.messages += message("m1", "conv-a", MessageDirection.INCOMING, DeliveryStatus.DELIVERED)
        outboxDao.items += outbox("m1", "conv-a", peerA)

        deliver("a", InboundFixtures.receiptEnvelope("m1", ReceiptType.RECEIPT_TYPE_READ, now()))

        assertEquals(DeliveryStatus.DELIVERED, messageDao.getById("m1")!!.status)
        assertNull(messageDao.getById("m1")!!.readAtUnixMs)
        assertEquals(1, outboxDao.items.size)
    }

    @Test
    fun receiptFromWrongContactIgnored() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)
        contactDao.contacts += InboundFixtures.contact("b", peerB)
        conversationDao.conversations += conversation("conv-a", "a")
        messageDao.messages += message("m1", "conv-a", MessageDirection.OUTGOING, DeliveryStatus.SENT)
        outboxDao.items += outbox("m1", "conv-a", peerA)
        recipientDao.rows += recipient("m1", peerA, DeliveryStatus.SENT)

        deliver("b", InboundFixtures.receiptEnvelope("m1", ReceiptType.RECEIPT_TYPE_DELIVERED, now()))

        assertEquals(DeliveryStatus.SENT, messageDao.getById("m1")!!.status)
        assertEquals(1, outboxDao.items.size)

        deliver("a", InboundFixtures.receiptEnvelope("m1", ReceiptType.RECEIPT_TYPE_DELIVERED, now()))

        assertEquals(DeliveryStatus.DELIVERED, messageDao.getById("m1")!!.status)
        assertTrue(outboxDao.items.isEmpty())
    }

    @Test
    fun receiptTimestampClamped() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)
        conversationDao.conversations += conversation("conv-a", "a")
        val createdAt = now() - 60_000L
        messageDao.messages += message("past", "conv-a", MessageDirection.OUTGOING, DeliveryStatus.SENT, createdAt)
        messageDao.messages += message("future", "conv-a", MessageDirection.OUTGOING, DeliveryStatus.SENT, createdAt)
        recipientDao.rows += recipient("past", peerA, DeliveryStatus.SENT)
        recipientDao.rows += recipient("future", peerA, DeliveryStatus.SENT)

        deliver("a", InboundFixtures.receiptEnvelope("past", ReceiptType.RECEIPT_TYPE_DELIVERED, atUnixMs = 1L))
        val before = now()
        val tomorrow = before + 86_400_000L
        deliver("a", InboundFixtures.receiptEnvelope("future", ReceiptType.RECEIPT_TYPE_DELIVERED, tomorrow))
        val after = now()

        assertEquals(createdAt, messageDao.getById("past")!!.deliveredAtUnixMs)
        val futureTs = messageDao.getById("future")!!.deliveredAtUnixMs!!
        assertTrue("clamped to arrival time, got $futureTs", futureTs in before..after)
    }

    @Test
    fun readReceiptDoesNotDowngrade() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)
        conversationDao.conversations += conversation("conv-a", "a")
        messageDao.messages += message("m1", "conv-a", MessageDirection.OUTGOING, DeliveryStatus.READ)
            .copy(readAtUnixMs = 5_000L)
        recipientDao.rows += recipient("m1", peerA, DeliveryStatus.READ).copy(readAtUnixMs = 5_000L)

        deliver("a", InboundFixtures.receiptEnvelope("m1", ReceiptType.RECEIPT_TYPE_DELIVERED, now()))

        val stored = messageDao.getById("m1")!!
        assertEquals(DeliveryStatus.READ, stored.status)
        assertEquals(5_000L, stored.readAtUnixMs)
    }

    @Test
    fun batchedReadReceiptMarksEveryOwnedMessage() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)
        contactDao.contacts += InboundFixtures.contact("b", peerB)
        conversationDao.conversations += conversation("conv-a", "a")
        conversationDao.conversations += conversation("conv-b", "b")
        messageDao.messages += message("m1", "conv-a", MessageDirection.OUTGOING, DeliveryStatus.DELIVERED)
        messageDao.messages += message("m2", "conv-a", MessageDirection.OUTGOING, DeliveryStatus.SENT)
        messageDao.messages += message("other", "conv-b", MessageDirection.OUTGOING, DeliveryStatus.SENT)
        recipientDao.rows += recipient("m1", peerA, DeliveryStatus.DELIVERED)
        recipientDao.rows += recipient("m2", peerA, DeliveryStatus.SENT)
        recipientDao.rows += recipient("other", peerB, DeliveryStatus.SENT)

        val batch = InboundFixtures.receiptEnvelope("m1", ReceiptType.RECEIPT_TYPE_READ, now(), listOf("m2", "other"))
        deliver("a", batch)

        assertEquals(DeliveryStatus.READ, messageDao.getById("m1")!!.status)
        assertEquals(DeliveryStatus.READ, messageDao.getById("m2")!!.status)
        assertEquals(DeliveryStatus.SENT, messageDao.getById("other")!!.status)
    }

    @Test
    fun duplicateIdOtherConversationRejectedNoAck() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)
        contactDao.contacts += InboundFixtures.contact("b", peerB)
        conversationDao.conversations += conversation("conv-b", "b")
        messageDao.messages += message("m1", "conv-b", MessageDirection.INCOMING, DeliveryStatus.DELIVERED)

        deliver("a", InboundFixtures.chatEnvelope("m1", "shadow"))

        assertEquals("conv-b", messageDao.getById("m1")!!.conversationId)
        // The 1:1 conversation is now resolved (and created) before the id
        // collision is spotted, so an empty row for the sender exists; nothing of
        // theirs is written into it, and the collision is neither stored nor acked.
        val shadowed = conversationDao.getByContactId("a")
        assertNull(shadowed?.lastMessageId)
        assertEquals(0, shadowed?.unreadCount)
        assertEquals(1, messageDao.messages.size)
        assertTrue(messaging.sent.isEmpty())
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun duplicateInSameConversationReAckedNotDuplicated() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)

        deliver("a", InboundFixtures.chatEnvelope("m1"))
        deliver("a", InboundFixtures.chatEnvelope("m1"))

        assertEquals(1, messageDao.messages.size)
        assertEquals(1, conversationDao.getByContactId("a")!!.unreadCount)
        assertEquals(2, messaging.receiptsFor("m1").size)
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun inboundSentAtClamped() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)
        val before = now()

        deliver("a", InboundFixtures.chatEnvelope("old", sentAtUnixMs = 1L))
        deliver("a", InboundFixtures.chatEnvelope("future", sentAtUnixMs = before + 365L * 86_400_000L))
        val after = now()

        val old = messageDao.getById("old")!!
        assertTrue(old.createdAtUnixMs in before..after)
        assertTrue(old.sentAtUnixMs!! >= before - 7L * 86_400_000L)
        val future = messageDao.getById("future")!!
        assertTrue(future.sentAtUnixMs!! <= after + 5L * 60_000L)
        assertTrue(conversationDao.getByContactId("a")!!.lastActivityUnixMs in before..after)
    }

    @Test
    fun mutedConversationNoNotification() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)
        conversationDao.conversations += conversation("conv-a", "a").copy(muted = true)

        deliver("a", InboundFixtures.chatEnvelope("m1"))

        assertNotNull(messageDao.getById("m1"))
        assertEquals(1, conversationDao.getById("conv-a")!!.unreadCount)
        assertTrue(notifier.shown.isEmpty())
        assertEquals(1, messaging.receiptsFor("m1").size)
    }

    @Test
    fun activeConversationNoNotification() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA)
        conversationDao.conversations += conversation("conv-a", "a")
        ActiveConversationTracker.activeConversationId = "conv-a"

        deliver("a", InboundFixtures.chatEnvelope("m1"))

        assertNotNull(messageDao.getById("m1"))
        assertTrue(notifier.shown.isEmpty())

        ActiveConversationTracker.clear("conv-a")
        deliver("a", InboundFixtures.chatEnvelope("m2"))

        assertEquals(1, notifier.shown.size)
    }

    private suspend fun deliver(contactId: String, envelope: MessageEnvelope) {
        collector.handleIncoming(IncomingEnvelope(envelope = envelope, contactId = contactId, session = null))
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun conversation(id: String, contactId: String) = ConversationEntity(
        id = id,
        contactId = contactId,
        lastMessageId = null,
        lastActivityUnixMs = 0L,
        unreadCount = 0,
        muted = false,
    )

    private fun message(
        id: String,
        conversationId: String,
        direction: MessageDirection,
        status: DeliveryStatus,
        createdAtUnixMs: Long = now() - 1_000L,
    ) = MessageEntity(
        messageId = id,
        conversationId = conversationId,
        direction = direction,
        contentType = MessageContentType.TEXT,
        body = "text",
        replyToMessageId = null,
        status = status,
        createdAtUnixMs = createdAtUnixMs,
        sentAtUnixMs = createdAtUnixMs,
        deliveredAtUnixMs = null,
        readAtUnixMs = null,
    )

    private fun outbox(messageId: String, conversationId: String, recipient: PeerIdentity) = OutboxEntity(
        messageId = messageId,
        recipientIdentityHash = IdentityHashMatcher.routingKeyHex(recipient.identityHash),
        conversationId = conversationId,
        envelopeBytes = null,
        attemptCount = 0,
        nextAttemptUnixMs = 0L,
        lastError = null,
    )

    /** The per-recipient delivery row an outgoing message needs before a receipt counts. */
    private fun recipient(messageId: String, peer: PeerIdentity, status: DeliveryStatus) = MessageRecipientEntity(
        messageId = messageId,
        identityHash = IdentityHashMatcher.routingKeyHex(peer.identityHash),
        status = status,
        sentAtUnixMs = null,
        deliveredAtUnixMs = null,
        readAtUnixMs = null,
    )
}
