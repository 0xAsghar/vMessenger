package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.MessageRecipientEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.core.proto.app.v1.ReceiptType
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Receipts are authorized against the message's *recipient* rows, not against
 * the conversation: a group message legitimately collects one receipt per
 * member, and nobody else may move its ticks.
 */
class InboundReceiptHandlerTest {
    private val peerA = InboundFixtures.peer(0x0A)
    private val peerB = InboundFixtures.peer(0x0B)
    private val peerC = InboundFixtures.peer(0x0C)

    private lateinit var harness: InboundHarness
    private lateinit var handler: InboundReceiptHandler

    @Before
    fun setUp() {
        harness = InboundHarness()
        handler = harness.receiptHandler
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB)
        harness.contactDao.contacts += InboundFixtures.contact("c", peerC)
    }

    @Test
    fun `a receipt from someone who is not a recipient is ignored`() = runTest {
        seedOutgoing(recipients = listOf(peerA, peerB))

        handler.handle("c", receipt(ReceiptType.RECEIPT_TYPE_DELIVERED))

        assertEquals(DeliveryStatus.SENT, harness.messageDao.getById(MESSAGE_ID)?.status)
        assertTrue(harness.recipientDao.rows.all { it.status == DeliveryStatus.SENT })
        assertEquals(2, harness.outboxDao.forMessage(MESSAGE_ID).size)
    }

    @Test
    fun `a receipt for a message we did not send is ignored`() = runTest {
        seedOutgoing(recipients = listOf(peerA))
        harness.messageDao.messages.replaceAll { it.copy(direction = MessageDirection.INCOMING) }

        handler.handle("a", receipt(ReceiptType.RECEIPT_TYPE_READ))

        assertEquals(DeliveryStatus.SENT, harness.messageDao.getById(MESSAGE_ID)?.status)
        assertNull(harness.messageDao.getById(MESSAGE_ID)?.readAtUnixMs)
    }

    @Test
    fun `one member's receipt advances only that member`() = runTest {
        seedOutgoing(recipients = listOf(peerA, peerB))
        // B has not been reached yet, so the message may not read as delivered.
        harness.recipientDao.rows.replaceAll {
            if (it.identityHash == key(peerB)) it.copy(status = DeliveryStatus.QUEUED, sentAtUnixMs = null) else it
        }

        handler.handle("a", receipt(ReceiptType.RECEIPT_TYPE_DELIVERED))

        assertEquals(DeliveryStatus.DELIVERED, statusOf(peerA))
        assertEquals(DeliveryStatus.QUEUED, statusOf(peerB))
        // Still only "sent": one member has it, the other has not.
        assertEquals(DeliveryStatus.SENT, harness.messageDao.getById(MESSAGE_ID)?.status)
        // Only the confirming member's queue row is dropped.
        assertEquals(listOf(key(peerB)), harness.outboxDao.pendingRecipients(MESSAGE_ID))
    }

    @Test
    fun `a read receipt after a delivered one advances`() = runTest {
        seedOutgoing(recipients = listOf(peerA))

        handler.handle("a", receipt(ReceiptType.RECEIPT_TYPE_DELIVERED))
        handler.handle("a", receipt(ReceiptType.RECEIPT_TYPE_READ))

        assertEquals(DeliveryStatus.READ, statusOf(peerA))
        assertEquals(DeliveryStatus.READ, harness.messageDao.getById(MESSAGE_ID)?.status)
    }

    @Test
    fun `a delivered receipt after a read one does not regress`() = runTest {
        seedOutgoing(recipients = listOf(peerA))

        handler.handle("a", receipt(ReceiptType.RECEIPT_TYPE_READ))
        handler.handle("a", receipt(ReceiptType.RECEIPT_TYPE_DELIVERED))

        assertEquals(DeliveryStatus.READ, statusOf(peerA))
        assertEquals(DeliveryStatus.READ, harness.messageDao.getById(MESSAGE_ID)?.status)
    }

    /** One outgoing message already on the wire to every [recipients] member. */
    private suspend fun seedOutgoing(recipients: List<PeerIdentity>) {
        harness.messageDao.insert(
            MessageEntity(
                messageId = MESSAGE_ID,
                conversationId = CONVERSATION_ID,
                direction = MessageDirection.OUTGOING,
                contentType = MessageContentType.TEXT,
                body = "salam",
                replyToMessageId = null,
                status = DeliveryStatus.SENT,
                createdAtUnixMs = 1L,
                sentAtUnixMs = 1L,
                deliveredAtUnixMs = null,
                readAtUnixMs = null,
            ),
        )
        for (peer in recipients) {
            harness.recipientDao.rows += MessageRecipientEntity(
                messageId = MESSAGE_ID,
                identityHash = key(peer),
                status = DeliveryStatus.SENT,
                sentAtUnixMs = 1L,
                deliveredAtUnixMs = null,
                readAtUnixMs = null,
            )
            harness.outboxDao.enqueue(
                OutboxEntity(
                    messageId = MESSAGE_ID,
                    recipientIdentityHash = key(peer),
                    conversationId = CONVERSATION_ID,
                    envelopeBytes = null,
                    attemptCount = 0,
                    nextAttemptUnixMs = 0L,
                    lastError = null,
                ),
            )
        }
    }

    private fun statusOf(peer: PeerIdentity): DeliveryStatus? =
        harness.recipientDao.rows.firstOrNull { it.identityHash == key(peer) }?.status

    private fun key(peer: PeerIdentity): String = IdentityHashMatcher.routingKeyHex(peer.identityHash)

    private fun receipt(type: ReceiptType) =
        InboundFixtures.receiptEnvelope(MESSAGE_ID, type, System.currentTimeMillis()).receipt

    private companion object {
        const val MESSAGE_ID = "m1"
        const val CONVERSATION_ID = "g1"
    }
}
