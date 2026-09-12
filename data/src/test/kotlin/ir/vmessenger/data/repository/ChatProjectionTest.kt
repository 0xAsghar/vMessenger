package ir.vmessenger.data.repository

import ir.vmessenger.core.database.dao.ChatListRow
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.data.network.FakeOutboxDao
import ir.vmessenger.data.network.InboundFixtures
import ir.vmessenger.domain.model.MessagePreviewKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ir.vmessenger.domain.model.DeliveryStatus as DomainDeliveryStatus
import ir.vmessenger.domain.model.MessageDirection as DomainMessageDirection

/**
 * The windowed paging query, the reply JOIN and the chat-list JOIN, as the UI
 * consumes them: the fakes reproduce the ordering and join rules of the SQL,
 * and the mappers under test are the ones the repository uses verbatim.
 */
class ChatProjectionTest {
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var conversationDao: FakeConversationDao

    @Before
    fun setUp() {
        outboxDao = FakeOutboxDao()
        messageDao = FakeMessageDao(outboxDao.items)
        contactDao = FakeContactDao()
        conversationDao = FakeConversationDao(contactDao.contacts, messageDao.messages)
    }

    @Test
    fun windowReturnsTheNewestMessagesFirst() = runTest {
        seed(count = 10)

        val window = messageDao.observeConversation(CONVERSATION_ID, limit = 3).first()

        assertEquals(listOf("m9", "m8", "m7"), window.map { it.message.messageId })
    }

    @Test
    fun growingTheLimitLoadsEarlierMessagesWithoutMovingTheNewest() = runTest {
        seed(count = 10)

        val first = messageDao.observeConversation(CONVERSATION_ID, limit = 3).first()
        val grown = messageDao.observeConversation(CONVERSATION_ID, limit = 6).first()

        assertEquals(6, grown.size)
        // Index 0 stays the newest, which is what reverseLayout anchors on.
        assertEquals(first.map { it.message.messageId }, grown.take(3).map { it.message.messageId })
        assertEquals(listOf("m6", "m5", "m4"), grown.drop(3).map { it.message.messageId })
    }

    @Test
    fun countAndIndexOfDriveTheWindowGrowth() = runTest {
        seed(count = 10)

        assertEquals(10, messageDao.countForConversation(CONVERSATION_ID))
        assertEquals(0, messageDao.indexOf(CONVERSATION_ID, "m9"))
        assertEquals(7, messageDao.indexOf(CONVERSATION_ID, "m2"))
        assertEquals(-1, messageDao.indexOf(CONVERSATION_ID, "nope"))
    }

    @Test
    fun replyProjectionCarriesTheQuotedPreview() = runTest {
        messageDao.insert(
            message("m1", createdAt = 10L, body = "the original").copy(direction = MessageDirection.OUTGOING),
        )
        messageDao.insert(message("m2", createdAt = 20L, body = "the answer").copy(replyToMessageId = "m1"))

        val newest = messageDao.observeConversation(CONVERSATION_ID, limit = 1).first().single().toChatMessage()

        assertEquals("m1", newest.replyTo?.messageId)
        assertEquals("the original", newest.replyTo?.preview)
        assertEquals(MessagePreviewKind.TEXT, newest.replyTo?.contentType)
        assertTrue(newest.replyTo?.senderIsMe == true)
    }

    @Test
    fun replyToAMessageOutsideTheConversationDoesNotResolve() = runTest {
        // The other conversation's message must never leak into this chat's quote.
        messageDao.insert(
            message("other", createdAt = 10L, body = "private").copy(conversationId = "c2"),
        )
        messageDao.insert(message("m2", createdAt = 20L, body = "spoof").copy(replyToMessageId = "other"))

        val newest = messageDao.observeConversation(CONVERSATION_ID, limit = 1).first().single().toChatMessage()

        assertEquals("other", newest.replyToMessageId)
        assertNull(newest.replyTo)
    }

    @Test
    fun failedSendCarriesTheOutboxError() = runTest {
        messageDao.insert(message("m1", createdAt = 10L, body = "nope"))
        outboxDao.enqueue(
            OutboxEntity(
                messageId = "m1",
                conversationId = CONVERSATION_ID,
                recipientIdentityHash = RECIPIENT,
                envelopeBytes = null,
                attemptCount = 3,
                nextAttemptUnixMs = 0L,
                lastError = "peer protocol major=1",
            ),
        )

        val newest = messageDao.observeConversation(CONVERSATION_ID, limit = 1).first().single().toChatMessage()

        assertEquals("peer protocol major=1", newest.lastError)
    }

    @Test
    fun chatListJoinFillsEveryFieldOfTheRow() = runTest {
        val peer = InboundFixtures.peer(0x0A)
        contactDao.contacts += InboundFixtures.contact(CONTACT_ID, peer)
        messageDao.insert(
            message("m1", createdAt = 42L, body = "last word").copy(status = DeliveryStatus.READ),
        )
        conversationDao.conversations += ConversationEntity(
            id = CONVERSATION_ID,
            contactId = CONTACT_ID,
            lastMessageId = "m1",
            lastActivityUnixMs = 42L,
            unreadCount = 3,
            muted = true,
        )

        val summary = conversationDao.observeChatList().first().single().toSummary()

        assertEquals(CONVERSATION_ID, summary.id)
        assertEquals(CONTACT_ID, summary.contactId)
        assertEquals("Contact $CONTACT_ID", summary.contactName)
        assertTrue(peer.identityHash.contentEquals(summary.identityHash))
        assertEquals("last word", summary.preview)
        assertEquals(MessagePreviewKind.TEXT, summary.previewKind)
        assertEquals(DomainMessageDirection.OUTGOING, summary.lastDirection)
        assertEquals(DomainDeliveryStatus.READ, summary.lastStatus)
        assertEquals(42L, summary.lastActivityUnixMs)
        assertEquals(3, summary.unreadCount)
        assertTrue(summary.muted)
    }

    @Test
    fun chatListRowOfAnEmptyConversationHasNoPreview() {
        val summary = ChatListRow(
            conversationId = CONVERSATION_ID,
            contactId = CONTACT_ID,
            groupId = null,
            displayName = null,
            identityHash = null,
            groupAvatarSeed = null,
            lastSenderName = null,
            lastMessageId = null,
            lastBody = null,
            lastAttachmentName = null,
            lastContentType = null,
            lastDirection = null,
            lastStatus = null,
            lastCreatedAtUnixMs = null,
            lastActivityUnixMs = 7L,
            unreadCount = 0,
            muted = false,
        ).toSummary()

        // The contact id stands in for a name that is not there yet.
        assertEquals(CONTACT_ID, summary.contactName)
        assertEquals(0, summary.identityHash.size)
        assertNull(summary.preview)
        assertNull(summary.previewKind)
        assertNull(summary.lastDirection)
        assertNull(summary.lastStatus)
    }

    @Test
    fun anAttachmentPreviewFallsBackToItsFileName() {
        val summary = ChatListRow(
            conversationId = CONVERSATION_ID,
            contactId = CONTACT_ID,
            groupId = null,
            displayName = "Ali",
            identityHash = byteArrayOf(1, 2, 3),
            groupAvatarSeed = null,
            lastSenderName = null,
            lastMessageId = "m1",
            lastBody = null,
            lastAttachmentName = "report.pdf",
            lastContentType = MessageContentType.FILE,
            lastDirection = MessageDirection.INCOMING,
            lastStatus = DeliveryStatus.DELIVERED,
            lastCreatedAtUnixMs = 9L,
            lastActivityUnixMs = 9L,
            unreadCount = 1,
            muted = false,
        ).toSummary()

        assertEquals("report.pdf", summary.preview)
        assertEquals(MessagePreviewKind.FILE, summary.previewKind)
    }

    private suspend fun seed(count: Int) {
        repeat(count) { index ->
            messageDao.insert(message("m$index", createdAt = index.toLong(), body = "body $index"))
        }
    }

    private fun message(id: String, createdAt: Long, body: String? = null) = MessageEntity(
        messageId = id,
        conversationId = CONVERSATION_ID,
        direction = MessageDirection.OUTGOING,
        contentType = MessageContentType.TEXT,
        body = body ?: "body $id",
        replyToMessageId = null,
        status = DeliveryStatus.QUEUED,
        createdAtUnixMs = createdAt,
        sentAtUnixMs = null,
        deliveredAtUnixMs = null,
        readAtUnixMs = null,
    )

    private companion object {
        const val CONVERSATION_ID = "c1"
        const val CONTACT_ID = "a"

        /** Routing key of the one recipient; the outbox row is keyed by it. */
        const val RECIPIENT = "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"
    }
}
