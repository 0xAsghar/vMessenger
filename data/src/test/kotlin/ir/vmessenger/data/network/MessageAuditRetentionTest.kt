package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.MessageRevisionKind
import ir.vmessenger.core.proto.app.v1.MessageDelete
import ir.vmessenger.core.proto.app.v1.MessageEdit
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the audit-retention policy does, and — more importantly — what it does not do.
 *
 * The feature reverses a privacy property: normally a delete really does erase the text. These
 * tests are the boundary of that reversal. It applies to one group, only because that group's
 * creator switched it on, and to nothing else.
 */
class MessageAuditRetentionTest {
    private val peer = InboundFixtures.peer(0x0A)

    private lateinit var harness: InboundHarness
    private lateinit var handler: MessageRevisionHandler

    @Before
    fun setUp() {
        harness = InboundHarness()
        handler = harness.revisionHandler
        harness.contactDao.contacts += InboundFixtures.contact("a", peer)
    }

    @Test
    fun `a one to one delete still erases the text and the file`() = runTest {
        // The case that must not regress. A private chat has no creator to author a policy and no
        // admin to read it, so a delete keeps meaning what it has always meant.
        seedDirect()
        harness.messageDao.messages += incoming(DIRECT_CONVERSATION, "a secret", ATTACHMENT)

        handler.handle("a", delete())

        assertTrue("nothing may be captured for a 1:1 chat", harness.historyDao.rows.isEmpty())
        assertEquals(listOf(ATTACHMENT), harness.attachmentFiles.deleted)
        assertNull(harness.messageDao.messages.single().body)
    }

    @Test
    fun `a group delete with retention off erases exactly as before`() = runTest {
        seedGroup(retention = false)
        harness.messageDao.messages += incoming(GROUP_CONVERSATION, "a secret", ATTACHMENT)

        handler.handle("a", delete())

        assertTrue(harness.historyDao.rows.isEmpty())
        assertEquals(listOf(ATTACHMENT), harness.attachmentFiles.deleted)
    }

    @Test
    fun `a group delete with retention on captures the text and keeps the file`() = runTest {
        seedGroup(retention = true)
        harness.messageDao.messages += incoming(GROUP_CONVERSATION, "a secret", ATTACHMENT)

        handler.handle("a", delete())

        val captured = harness.historyDao.rows.single()
        assertEquals("a secret", captured.body)
        assertEquals(MessageRevisionKind.DELETE, captured.revision)
        assertEquals(GROUP_ID, captured.groupId)
        assertEquals(IdentityHashMatcher.routingKeyHex(peer.identityHash), captured.authorIdentityHash)
        // The file stays because a captured row points at it; deleting it would leave the capture
        // naming an attachment that is not there.
        assertTrue("the file must survive a captured delete", harness.attachmentFiles.deleted.isEmpty())
        // The message itself is still a tombstone: retention is for admins, not for the bubble.
        assertEquals(MessageContentType.DELETED, harness.messageDao.messages.single().contentType)
        assertNull(harness.messageDao.messages.single().body)
    }

    @Test
    fun `an edit under retention keeps what the message said before`() = runTest {
        seedGroup(retention = true)
        harness.messageDao.messages += incoming(GROUP_CONVERSATION, "before", null)

        handler.handle("a", edit("after"))

        val captured = harness.historyDao.rows.single()
        assertEquals("before", captured.body)
        assertEquals(MessageRevisionKind.EDIT, captured.revision)
        assertEquals("after", harness.messageDao.messages.single().body)
    }

    private fun seedDirect() {
        harness.conversationDao.conversations += conversation(DIRECT_CONVERSATION, contactId = "a", groupId = null)
    }

    private suspend fun seedGroup(retention: Boolean) {
        harness.conversationDao.conversations += conversation(GROUP_CONVERSATION, contactId = null, groupId = GROUP_ID)
        harness.groupDao.insert(
            GroupEntity(
                id = GROUP_ID,
                name = "Team",
                creatorIdentityHash = "cc",
                createdAtUnixMs = 1,
                version = 1,
                closed = false,
                avatarSeed = GROUP_ID,
                auditRetention = retention,
            ),
        )
    }

    private fun conversation(id: String, contactId: String?, groupId: String?) = ConversationEntity(
        id = id,
        contactId = contactId,
        groupId = groupId,
        lastMessageId = null,
        lastActivityUnixMs = 0,
        unreadCount = 0,
        muted = false,
    )

    private fun incoming(conversationId: String, body: String, attachment: String?) = MessageEntity(
        messageId = MESSAGE_ID,
        conversationId = conversationId,
        direction = MessageDirection.INCOMING,
        contentType = MessageContentType.TEXT,
        body = body,
        replyToMessageId = null,
        status = DeliveryStatus.DELIVERED,
        createdAtUnixMs = 1,
        sentAtUnixMs = 1,
        deliveredAtUnixMs = 1,
        readAtUnixMs = null,
        attachmentPath = attachment,
        senderIdentityHash = IdentityHashMatcher.routingKeyHex(peer.identityHash),
    )

    private fun delete(): MessageEnvelope = envelope {
        setMessageDelete(
            MessageDelete.newBuilder()
                .setTargetMessageId(ByteString.copyFromUtf8(MESSAGE_ID))
                .setDeletedAtUnixMs(500),
        )
    }

    private fun edit(text: String): MessageEnvelope = envelope {
        setMessageEdit(
            MessageEdit.newBuilder()
                .setTargetMessageId(ByteString.copyFromUtf8(MESSAGE_ID))
                .setNewText(text)
                .setEditedAtUnixMs(500),
        )
    }

    /** Carries the group id only when the seeded conversation is a group one; resolution keys on it. */
    private fun envelope(content: MessageEnvelope.Builder.() -> Unit): MessageEnvelope {
        val builder = MessageEnvelope.newBuilder().setMessageId(ByteString.copyFromUtf8("env"))
        if (harness.conversationDao.conversations.any { it.groupId == GROUP_ID }) {
            builder.groupId = ByteString.copyFromUtf8(GROUP_ID)
        }
        builder.content()
        return builder.build()
    }

    private companion object {
        const val MESSAGE_ID = "m1"
        const val DIRECT_CONVERSATION = "conv-direct"
        const val GROUP_CONVERSATION = "conv-group"
        const val GROUP_ID = "0102030405060708090a0b0c0d0e0f10"
        const val ATTACHMENT = "/files/photo.vma1"
    }
}
