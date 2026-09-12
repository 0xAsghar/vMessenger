package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.getOrThrow
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.MessageRecipientEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.data.network.FakeAttachmentFileStore
import ir.vmessenger.data.network.FakeOutboxDao
import ir.vmessenger.data.network.InboundFixtures
import ir.vmessenger.data.network.InboundHarness
import ir.vmessenger.data.network.OutboxWaker
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory drafts; the real one is an Android `DataStore`. */
class FakeConversationDraftStore : ConversationDraftStore {
    private val drafts = mutableMapOf<String, MutableStateFlow<String>>()

    override fun observe(conversationId: String): Flow<String> = flowFor(conversationId)

    override suspend fun save(conversationId: String, text: String) {
        flowFor(conversationId).value = text
    }

    override suspend fun clear(conversationId: String) {
        flowFor(conversationId).value = ""
    }

    private fun flowFor(conversationId: String) = drafts.getOrPut(conversationId) { MutableStateFlow("") }
}

class RecordingOutboxWaker : OutboxWaker {
    var wakeCount = 0

    override fun wake() {
        wakeCount++
    }
}

/** The write half of the conversation repository: queueing, fan-out, deletes, mute, retry and drafts. */
@Suppress("TooManyFunctions") // one test per mutation the writer owns
class ConversationWriterTest {
    private val peerA = InboundFixtures.peer(0x0A)
    private val peerB = InboundFixtures.peer(0x0B)

    private lateinit var harness: InboundHarness
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var conversationDao: FakeConversationDao
    private lateinit var recipientDao: FakeMessageRecipientDao
    private lateinit var draftStore: FakeConversationDraftStore
    private lateinit var attachmentFiles: FakeAttachmentFileStore
    private lateinit var waker: RecordingOutboxWaker
    private lateinit var writer: ConversationWriter

    @Before
    fun setUp() {
        harness = InboundHarness()
        outboxDao = harness.outboxDao
        messageDao = harness.messageDao
        conversationDao = harness.conversationDao
        recipientDao = harness.recipientDao
        draftStore = harness.draftStore
        attachmentFiles = harness.attachmentFiles
        waker = harness.waker
        writer = harness.writer
        harness.contactDao.contacts += InboundFixtures.contact(CONTACT_ID, peerA)
        conversationDao.conversations += ConversationEntity(
            id = CONVERSATION_ID,
            contactId = CONTACT_ID,
            lastMessageId = null,
            lastActivityUnixMs = 0L,
            unreadCount = 0,
            muted = false,
        )
    }

    @Test
    fun sendTextQueuesTheMessageAndWakesTheDispatcher() = runTest {
        val messageId = writer.sendText(CONVERSATION_ID, "salam", replyToMessageId = null).getOrThrow()

        val stored = messageDao.getById(messageId)
        assertNotNull(stored)
        assertEquals("salam", stored!!.body)
        assertEquals(DeliveryStatus.QUEUED, stored.status)
        assertEquals(MessageDirection.OUTGOING, stored.direction)
        assertEquals(1, outboxDao.items.size)
        assertEquals(messageId, conversationDao.getById(CONVERSATION_ID)?.lastMessageId)
        assertEquals(1, waker.wakeCount)
    }

    @Test
    fun sendTextStoresTheQuotedMessageIdAndClearsTheDraft() = runTest {
        draftStore.save(CONVERSATION_ID, "half typed")

        val messageId = writer.sendText(CONVERSATION_ID, "reply", replyToMessageId = "quoted-1").getOrThrow()

        assertEquals("quoted-1", messageDao.getById(messageId)?.replyToMessageId)
        assertEquals("", draftStore.observe(CONVERSATION_ID).first())
    }

    @Test
    fun `a one to one chat fans out to exactly one recipient`() = runTest {
        val messageId = writer.sendText(CONVERSATION_ID, "salam", replyToMessageId = null).getOrThrow()

        assertEquals(listOf(routingKeyOf(peerA)), recipientDao.rows.map { it.identityHash })
        assertEquals(listOf(DeliveryStatus.QUEUED), recipientDao.rows.map { it.status })
        assertEquals(listOf(routingKeyOf(peerA)), outboxDao.items.map { it.recipientIdentityHash })
        assertEquals(messageId, outboxDao.items.single().messageId)
    }

    @Test
    fun `a group message queues one recipient row and one outbox row per member`() = runTest {
        seedGroup()

        val messageId = writer.sendText(GROUP_CONVERSATION_ID, "salam", replyToMessageId = null).getOrThrow()

        assertEquals(1, messageDao.messages.count { it.messageId == messageId })
        val expected = setOf(routingKeyOf(peerA), routingKeyOf(peerB))
        assertEquals(expected, recipientDao.forMessage(messageId).map { it.identityHash }.toSet())
        assertEquals(expected, outboxDao.forMessage(messageId).map { it.recipientIdentityHash }.toSet())
        // Ordinary messages are rebuilt from the row; only group controls carry bytes.
        assertTrue(outboxDao.forMessage(messageId).all { it.envelopeBytes == null })
    }

    @Test
    fun `a conversation with no reachable recipient stores the message failed`() = runTest {
        harness.contactDao.contacts.replaceAll { it.copy(blocked = true) }

        val result = writer.sendText(CONVERSATION_ID, "salam", replyToMessageId = null)

        assertTrue(result is AppResult.Error)
        assertEquals(AppError.NoReachableMembers, (result as AppResult.Error).error)
        val stored = messageDao.messages.single()
        assertEquals(DeliveryStatus.FAILED, stored.status)
        assertTrue(outboxDao.items.isEmpty())
        assertTrue(recipientDao.rows.isEmpty())
        // The chat list still moves: the user has to be able to see the failure.
        assertEquals(stored.messageId, conversationDao.getById(CONVERSATION_ID)?.lastMessageId)
    }

    @Test
    fun draftIsSavedObservedAndCleared() = runTest {
        writer.saveDraft(CONVERSATION_ID, "in progress")
        assertEquals("in progress", writer.observeDraft(CONVERSATION_ID).first())

        writer.saveDraft(CONVERSATION_ID, "")
        assertEquals("", writer.observeDraft(CONVERSATION_ID).first())
    }

    @Test
    fun deleteForMeRemovesOnlyThatMessageLocally() = runTest {
        messageDao.insert(message("m1", createdAt = 10L))
        messageDao.insert(message("m2", createdAt = 20L))
        outboxDao.enqueue(outboxRow("m1", peerA))
        conversationDao.setLastMessageId(CONVERSATION_ID, "m2")

        writer.deleteMessageForMe("m1")

        assertNull(messageDao.getById("m1"))
        assertNotNull(messageDao.getById("m2"))
        // Nothing is queued for the peer: delete-for-me is purely local.
        assertTrue(outboxDao.items.none { it.messageId == "m1" })
        assertEquals("m2", conversationDao.getById(CONVERSATION_ID)?.lastMessageId)
    }

    @Test
    fun `delete for me drops every recipient of a group message`() = runTest {
        messageDao.insert(message("m1", createdAt = 10L))
        outboxDao.enqueue(outboxRow("m1", peerA))
        outboxDao.enqueue(outboxRow("m1", peerB))

        writer.deleteMessageForMe("m1")

        assertTrue(outboxDao.items.isEmpty())
    }

    @Test
    fun deleteForMeRepointsThePreviewWhenTheNewestMessageGoes() = runTest {
        messageDao.insert(message("m1", createdAt = 10L))
        messageDao.insert(message("m2", createdAt = 20L))
        conversationDao.setLastMessageId(CONVERSATION_ID, "m2")

        writer.deleteMessageForMe("m2")

        assertEquals("m1", conversationDao.getById(CONVERSATION_ID)?.lastMessageId)
    }

    @Test
    fun deleteForMeErasesTheStoredAttachment() = runTest {
        messageDao.insert(message("m1", createdAt = 10L).copy(attachmentPath = "/files/a.enc"))

        writer.deleteMessageForMe("m1")

        assertEquals(listOf("/files/a.enc"), attachmentFiles.deleted)
    }

    @Test
    fun deleteConversationRemovesMessagesQueueAttachmentsAndDraft() = runTest {
        messageDao.insert(message("m1", createdAt = 10L).copy(attachmentPath = "/files/a.enc"))
        messageDao.insert(message("m2", createdAt = 20L))
        outboxDao.enqueue(outboxRow("m1", peerA))
        draftStore.save(CONVERSATION_ID, "unsent")

        writer.deleteConversation(CONVERSATION_ID)

        assertNull(conversationDao.getById(CONVERSATION_ID))
        assertTrue(messageDao.messages.isEmpty())
        assertTrue(outboxDao.items.isEmpty())
        assertEquals(listOf("/files/a.enc"), attachmentFiles.deleted)
        assertEquals("", draftStore.observe(CONVERSATION_ID).first())
    }

    @Test
    fun muteAndUnmuteFlipTheFlag() = runTest {
        writer.setMuted(CONVERSATION_ID, true)
        assertTrue(conversationDao.getById(CONVERSATION_ID)!!.muted)

        writer.setMuted(CONVERSATION_ID, false)
        assertFalse(conversationDao.getById(CONVERSATION_ID)!!.muted)
    }

    @Test
    fun retryClearsBackoffAndFailureOfAQueuedMessage() = runTest {
        messageDao.insert(message("m1", createdAt = 10L).copy(status = DeliveryStatus.FAILED))
        recipientDao.rows += recipientRow("m1", peerA, DeliveryStatus.QUEUED)
        outboxDao.enqueue(
            outboxRow("m1", peerA).copy(attemptCount = 7, nextAttemptUnixMs = FAR_FUTURE, lastError = "peer major=1"),
        )

        writer.retry("m1")

        val row = outboxDao.items.single()
        assertEquals(0, row.attemptCount)
        assertEquals(0L, row.nextAttemptUnixMs)
        assertNull(row.lastError)
        assertEquals(DeliveryStatus.QUEUED, messageDao.getById("m1")?.status)
        assertEquals(1, waker.wakeCount)
    }

    @Test
    fun retryReQueuesAMessageTheDispatcherHadGivenUpOn() = runTest {
        messageDao.insert(message("m1", createdAt = 10L).copy(status = DeliveryStatus.FAILED))

        writer.retry("m1")

        assertEquals(1, outboxDao.items.size)
        assertEquals(CONVERSATION_ID, outboxDao.items.single().conversationId)
        assertEquals(DeliveryStatus.QUEUED, messageDao.getById("m1")?.status)
        // A message that never had delivery rows is resolved from scratch.
        assertEquals(listOf(routingKeyOf(peerA)), recipientDao.forMessage("m1").map { it.identityHash })
    }

    @Test
    fun `retry only re queues members with nothing in flight`() = runTest {
        seedGroup()
        messageDao.insert(groupMessage("m1").copy(status = DeliveryStatus.QUEUED))
        recipientDao.rows += recipientRow("m1", peerA, DeliveryStatus.SENT)
        recipientDao.rows += recipientRow("m1", peerB, DeliveryStatus.FAILED)
        // Only A is still waiting on the wire; B's row was dropped when it failed.
        outboxDao.enqueue(outboxRow("m1", peerA, GROUP_CONVERSATION_ID).copy(nextAttemptUnixMs = FAR_FUTURE))

        writer.retry("m1")

        val requeued = outboxDao.forMessage("m1").single { it.recipientIdentityHash == routingKeyOf(peerB) }
        assertEquals(0, requeued.attemptCount)
        assertEquals(2, outboxDao.forMessage("m1").size)
        // A's row was only un-backed-off, never duplicated into a second send.
        val stillWaiting = outboxDao.forMessage("m1").single { it.recipientIdentityHash == routingKeyOf(peerA) }
        assertEquals(0L, stillWaiting.nextAttemptUnixMs)
    }

    @Test
    fun `retry never re queues a member that already has the message`() = runTest {
        seedGroup()
        messageDao.insert(groupMessage("m1").copy(status = DeliveryStatus.SENT))
        recipientDao.rows += recipientRow("m1", peerA, DeliveryStatus.DELIVERED)
        recipientDao.rows += recipientRow("m1", peerB, DeliveryStatus.READ)

        writer.retry("m1")

        assertTrue(outboxDao.forMessage("m1").isEmpty())
        assertEquals(DeliveryStatus.SENT, messageDao.getById("m1")?.status)
    }

    @Test
    fun retryLeavesASentMessageAwaitingItsReceipt() = runTest {
        messageDao.insert(message("m1", createdAt = 10L).copy(status = DeliveryStatus.SENT))
        recipientDao.rows += recipientRow("m1", peerA, DeliveryStatus.SENT)
        outboxDao.enqueue(outboxRow("m1", peerA).copy(nextAttemptUnixMs = FAR_FUTURE))

        writer.retry("m1")

        assertEquals(DeliveryStatus.SENT, messageDao.getById("m1")?.status)
        assertEquals(0L, outboxDao.items.single().nextAttemptUnixMs)
    }

    @Test
    fun `a queued group control carries its envelope to every recipient`() = runTest {
        seedGroup()
        val envelope = byteArrayOf(1, 2, 3)

        val messageId = writer.queueGroupControl(
            conversationId = GROUP_CONVERSATION_ID,
            text = "added",
            envelope = envelope,
            recipients = listOf(routingKeyOf(peerA), routingKeyOf(peerB)),
        )

        assertEquals(MessageContentType.GROUP_CONTROL, messageDao.getById(messageId)?.contentType)
        assertEquals(2, outboxDao.forMessage(messageId).size)
        assertTrue(outboxDao.forMessage(messageId).all { envelope.contentEquals(it.envelopeBytes) })
    }

    /** A group conversation with us plus A and B; C is a member we hold no contact for. */
    private suspend fun seedGroup() {
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB)
        harness.groupDao.insert(GroupFixtures.group(creatorKey = routingKeyOf(peerA)))
        harness.groupDao.upsertMembers(
            listOf(
                GroupFixtures.member(seed = 0x0A, role = GroupMemberRole.CREATOR),
                GroupFixtures.member(seed = 0x0B),
                GroupFixtures.member(seed = 0x0C),
                GroupFixtures.member(seed = SELF_SEED),
            ),
        )
        conversationDao.conversations += ConversationEntity(
            id = GROUP_CONVERSATION_ID,
            contactId = null,
            groupId = GroupFixtures.GROUP_ID,
            lastMessageId = null,
            lastActivityUnixMs = 0L,
            unreadCount = 0,
            muted = false,
        )
    }

    private fun routingKeyOf(peer: PeerIdentity): String = IdentityHashMatcher.routingKeyHex(peer.identityHash)

    private fun message(id: String, createdAt: Long) = MessageEntity(
        messageId = id,
        conversationId = CONVERSATION_ID,
        direction = MessageDirection.OUTGOING,
        contentType = MessageContentType.TEXT,
        body = "body $id",
        replyToMessageId = null,
        status = DeliveryStatus.QUEUED,
        createdAtUnixMs = createdAt,
        sentAtUnixMs = null,
        deliveredAtUnixMs = null,
        readAtUnixMs = null,
    )

    private fun groupMessage(id: String) = message(id, createdAt = 10L).copy(conversationId = GROUP_CONVERSATION_ID)

    private fun outboxRow(
        id: String,
        peer: PeerIdentity,
        conversationId: String = CONVERSATION_ID,
    ) = OutboxEntity(
        messageId = id,
        recipientIdentityHash = routingKeyOf(peer),
        conversationId = conversationId,
        envelopeBytes = null,
        attemptCount = 0,
        nextAttemptUnixMs = 0L,
        lastError = null,
    )

    private fun recipientRow(
        id: String,
        peer: PeerIdentity,
        status: DeliveryStatus,
    ) = MessageRecipientEntity(
        messageId = id,
        identityHash = routingKeyOf(peer),
        status = status,
        sentAtUnixMs = null,
        deliveredAtUnixMs = null,
        readAtUnixMs = null,
    )

    private companion object {
        const val CONVERSATION_ID = "c1"
        const val GROUP_CONVERSATION_ID = "g1"
        const val CONTACT_ID = "a"
        const val FAR_FUTURE = 9_999_999_999L
        const val SELF_SEED: Byte = 0x01
    }
}
