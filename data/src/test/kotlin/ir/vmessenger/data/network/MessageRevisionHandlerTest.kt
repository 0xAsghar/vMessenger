package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.MessageDelete
import ir.vmessenger.core.proto.app.v1.MessageEdit
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Who is allowed to rewrite history.
 *
 * With no server to arbitrate, the only authority is ownership: a peer may revise a message that
 * arrived *from them*, and nothing else. These are the cases that would let someone edit words
 * they did not write.
 */
class MessageRevisionHandlerTest {
    private val peerA = InboundFixtures.peer(0x0A)
    private val peerB = InboundFixtures.peer(0x0B)

    private lateinit var harness: InboundHarness
    private lateinit var handler: MessageRevisionHandler

    @Before
    fun setUp() {
        harness = InboundHarness()
        handler = harness.revisionHandler
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB)
        harness.conversationDao.conversations += ConversationEntity(
            id = CONVERSATION,
            contactId = "a",
            groupId = null,
            lastMessageId = null,
            lastActivityUnixMs = 0,
            unreadCount = 0,
            muted = false,
        )
    }

    @Test
    fun `an edit from the sender replaces the text and marks the bubble edited`() = runTest {
        harness.messageDao.messages += incoming("m1", "salam")

        handler.handle("a", edit("m1", "salam!", at = 100))

        val stored = harness.messageDao.messages.single()
        assertEquals("salam!", stored.body)
        assertEquals(100L, stored.editedAtUnixMs)
    }

    @Test
    fun `a peer cannot edit a message we sent`() = runTest {
        harness.messageDao.messages += incoming("m1", "salam").copy(direction = MessageDirection.OUTGOING)

        handler.handle("a", edit("m1", "hacked", at = 100))

        assertEquals("salam", harness.messageDao.messages.single().body)
    }

    @Test
    fun `a peer cannot edit a message in someone else's conversation`() = runTest {
        harness.messageDao.messages += incoming("m1", "salam")

        // B is a contact, but this message belongs to A's thread.
        handler.handle("b", edit("m1", "hacked", at = 100))

        assertEquals("salam", harness.messageDao.messages.single().body)
    }

    @Test
    fun `a replayed edit cannot reinstate older text`() = runTest {
        harness.messageDao.messages += incoming("m1", "salam")
        handler.handle("a", edit("m1", "second", at = 200))

        handler.handle("a", edit("m1", "first", at = 100))

        assertEquals("second", harness.messageDao.messages.single().body)
    }

    @Test
    fun `a delete leaves a tombstone rather than removing the row`() = runTest {
        harness.messageDao.messages += incoming("m1", "salam")

        handler.handle("a", delete("m1"))

        val stored = harness.messageDao.messages.single()
        assertEquals(MessageContentType.DELETED, stored.contentType)
        assertNull(stored.body)
        // Kept for the message info sheet, by the sender's clock as the delete carried it.
        assertEquals(500L, stored.deletedAtUnixMs)
    }

    @Test
    fun `an edited message that is then deleted keeps both times`() = runTest {
        harness.messageDao.messages += incoming("m1", "first")
        handler.handle("a", edit("m1", "second", at = 100))

        handler.handle("a", delete("m1"))

        val stored = harness.messageDao.messages.single()
        assertEquals(100L, stored.editedAtUnixMs)
        assertEquals(500L, stored.deletedAtUnixMs)
    }

    @Test
    fun `a delete that arrives before its message still writes the tombstone`() = runTest {
        handler.handle("a", delete("m-future"))

        val stored = harness.messageDao.messages.single()
        assertEquals("m-future", stored.messageId)
        assertEquals(MessageContentType.DELETED, stored.contentType)
        assertEquals(500L, stored.deletedAtUnixMs)
        // The collector's per-conversation dedup then drops the original when it lands.
        assertNotNull(harness.messageDao.getByIdInConversation("m-future", CONVERSATION))
    }

    @Test
    fun `an unreasonably long target id is refused before it reaches a query`() = runTest {
        harness.messageDao.messages += incoming("m1", "salam")

        handler.handle("a", edit("x".repeat(65), "hacked", at = 100))

        assertEquals("salam", harness.messageDao.messages.single().body)
    }

    private fun incoming(id: String, body: String) = MessageEntity(
        messageId = id,
        conversationId = CONVERSATION,
        direction = MessageDirection.INCOMING,
        contentType = MessageContentType.TEXT,
        body = body,
        replyToMessageId = null,
        status = DeliveryStatus.DELIVERED,
        createdAtUnixMs = 1,
        sentAtUnixMs = 1,
        deliveredAtUnixMs = 1,
        readAtUnixMs = null,
        senderIdentityHash = IdentityHashMatcher.routingKeyHex(peerA.identityHash),
    )

    private fun edit(targetId: String, text: String, at: Long): MessageEnvelope =
        MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("env-$at"))
            .setMessageEdit(
                MessageEdit.newBuilder()
                    .setTargetMessageId(ByteString.copyFromUtf8(targetId))
                    .setNewText(text)
                    .setEditedAtUnixMs(at),
            )
            .build()

    private fun delete(targetId: String): MessageEnvelope =
        MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("env-del"))
            .setMessageDelete(
                MessageDelete.newBuilder()
                    .setTargetMessageId(ByteString.copyFromUtf8(targetId))
                    .setDeletedAtUnixMs(500),
            )
            .build()

    private companion object {
        const val CONVERSATION = "conv-a"
    }
}
