package ir.vmessenger.data.repository

import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.data.network.FakeAttachmentFileStore
import ir.vmessenger.data.network.FakeOutboxDao
import ir.vmessenger.data.network.OutboxWaker
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

/** The write half of the conversation repository: queueing, deletes, mute, retry and drafts. */
class ConversationWriterTest {
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var conversationDao: FakeConversationDao
    private lateinit var draftStore: FakeConversationDraftStore
    private lateinit var attachmentFiles: FakeAttachmentFileStore
    private lateinit var waker: RecordingOutboxWaker
    private lateinit var writer: ConversationWriter

    @Before
    fun setUp() {
        outboxDao = FakeOutboxDao()
        messageDao = FakeMessageDao(outboxDao.items)
        conversationDao = FakeConversationDao(mutableListOf(), messageDao.messages)
        draftStore = FakeConversationDraftStore()
        attachmentFiles = FakeAttachmentFileStore()
        waker = RecordingOutboxWaker()
        writer = ConversationWriter(
            conversationDao = conversationDao,
            messageDao = messageDao,
            outboxDao = outboxDao,
            draftStore = draftStore,
            attachmentFiles = attachmentFiles,
            outboxWaker = waker,
        )
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
        val messageId = writer.sendText(CONVERSATION_ID, "salam", replyToMessageId = null)

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

        val messageId = writer.sendText(CONVERSATION_ID, "reply", replyToMessageId = "quoted-1")

        assertEquals("quoted-1", messageDao.getById(messageId)?.replyToMessageId)
        assertEquals("", draftStore.observe(CONVERSATION_ID).first())
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
        outboxDao.enqueue(outboxRow("m1"))
        conversationDao.setLastMessageId(CONVERSATION_ID, "m2")

        writer.deleteMessageForMe("m1")

        assertNull(messageDao.getById("m1"))
        assertNotNull(messageDao.getById("m2"))
        // Nothing is queued for the peer: delete-for-me is purely local.
        assertTrue(outboxDao.items.none { it.messageId == "m1" })
        assertEquals("m2", conversationDao.getById(CONVERSATION_ID)?.lastMessageId)
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
        outboxDao.enqueue(outboxRow("m1"))
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
        outboxDao.enqueue(
            outboxRow("m1").copy(attemptCount = 7, nextAttemptUnixMs = FAR_FUTURE, lastError = "peer major=1"),
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
    }

    @Test
    fun retryLeavesASentMessageAwaitingItsReceipt() = runTest {
        messageDao.insert(message("m1", createdAt = 10L).copy(status = DeliveryStatus.SENT))
        outboxDao.enqueue(outboxRow("m1").copy(nextAttemptUnixMs = FAR_FUTURE))

        writer.retry("m1")

        assertEquals(DeliveryStatus.SENT, messageDao.getById("m1")?.status)
        assertEquals(0L, outboxDao.items.single().nextAttemptUnixMs)
    }

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

    private fun outboxRow(id: String) = OutboxEntity(
        messageId = id,
        conversationId = CONVERSATION_ID,
        recipientIdentityHash = RECIPIENT,
        envelopeBytes = null,
        attemptCount = 0,
        nextAttemptUnixMs = 0L,
        lastError = null,
    )

    private companion object {
        const val CONVERSATION_ID = "c1"
        const val CONTACT_ID = "a"
        const val FAR_FUTURE = 9_999_999_999L
    }
}
