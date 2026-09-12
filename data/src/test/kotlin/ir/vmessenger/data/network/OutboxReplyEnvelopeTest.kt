package ir.vmessenger.data.network

import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reply id has to survive the trip from the stored message into the wire envelope. */
class OutboxReplyEnvelopeTest {
    private val self = InboundFixtures.peer(0x01)

    @Test
    fun quotedMessageIdIsCarriedOnTheOutgoingChat() {
        val envelope = buildChatEnvelope(message(replyToMessageId = "quoted-1"), self)

        assertEquals("quoted-1", envelope.chat.replyToMessageId.toStringUtf8())
        assertEquals("m1", envelope.messageId.toStringUtf8())
        assertEquals("salam", envelope.chat.text)
    }

    @Test
    fun aPlainMessageLeavesTheReplyFieldEmpty() {
        val envelope = buildChatEnvelope(message(replyToMessageId = null), self)

        assertTrue(envelope.chat.replyToMessageId.isEmpty)
    }

    @Test
    fun aBlankReplyIdIsNotSent() {
        val envelope = buildChatEnvelope(message(replyToMessageId = "   "), self)

        assertTrue(envelope.chat.replyToMessageId.isEmpty)
    }

    @Test
    fun anIncomingQuotedIdIsAcceptedOnlyWhenItIsPlausible() {
        val accepted = buildChatEnvelope(message(replyToMessageId = "quoted-1"), self).chat
        assertEquals("quoted-1", accepted.quotedMessageIdOrNull())

        assertEquals(null, buildChatEnvelope(message(replyToMessageId = null), self).chat.quotedMessageIdOrNull())
        val oversized = buildChatEnvelope(message(replyToMessageId = "x".repeat(65)), self).chat
        assertEquals(null, oversized.quotedMessageIdOrNull())
    }

    private fun message(replyToMessageId: String?) = MessageEntity(
        messageId = "m1",
        conversationId = "c1",
        direction = MessageDirection.OUTGOING,
        contentType = MessageContentType.TEXT,
        body = "salam",
        replyToMessageId = replyToMessageId,
        status = DeliveryStatus.QUEUED,
        createdAtUnixMs = 1L,
        sentAtUnixMs = null,
        deliveredAtUnixMs = null,
        readAtUnixMs = null,
    )
}
