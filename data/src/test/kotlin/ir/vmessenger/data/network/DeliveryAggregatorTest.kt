package ir.vmessenger.data.network

import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.MessageRecipientEntity
import ir.vmessenger.data.repository.FakeMessageDao
import ir.vmessenger.data.repository.FakeMessageRecipientDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The one status a bubble renders, computed from the per-recipient rows. A group
 * message is N pairwise deliveries, so "delivered" may only mean *everyone* has
 * it; a 1:1 message is the one-row case of the same rules.
 */
class DeliveryAggregatorTest {
    private lateinit var messageDao: FakeMessageDao
    private lateinit var recipientDao: FakeMessageRecipientDao
    private lateinit var aggregator: DeliveryAggregator

    @Before
    fun setUp() {
        messageDao = FakeMessageDao()
        recipientDao = FakeMessageRecipientDao()
        aggregator = DeliveryAggregator(messageDao, recipientDao)
    }

    @Test
    fun `read only when every recipient read`() = runTest {
        seedMessage()
        recipientDao.rows += row("a", DeliveryStatus.READ, readAt = 300L)
        recipientDao.rows += row("b", DeliveryStatus.DELIVERED, deliveredAt = 200L)

        aggregator.recompute(MESSAGE_ID)
        assertEquals(DeliveryStatus.DELIVERED, statusOf())

        advance("b", DeliveryStatus.READ, readAt = 400L)
        aggregator.recompute(MESSAGE_ID)

        assertEquals(DeliveryStatus.READ, statusOf())
        // The bubble's timestamp is the last person to read it.
        assertEquals(400L, messageDao.getById(MESSAGE_ID)?.readAtUnixMs)
    }

    @Test
    fun `delivered only when every recipient has it`() = runTest {
        seedMessage()
        recipientDao.rows += row("a", DeliveryStatus.DELIVERED, deliveredAt = 100L)
        recipientDao.rows += row("b", DeliveryStatus.SENT, sentAt = 50L)

        aggregator.recompute(MESSAGE_ID)
        assertEquals(DeliveryStatus.SENT, statusOf())

        advance("b", DeliveryStatus.DELIVERED, deliveredAt = 700L)
        aggregator.recompute(MESSAGE_ID)

        assertEquals(DeliveryStatus.DELIVERED, statusOf())
        assertEquals(700L, messageDao.getById(MESSAGE_ID)?.deliveredAtUnixMs)
    }

    @Test
    fun `sent when any recipient has it and the rest are queued`() = runTest {
        seedMessage()
        recipientDao.rows += row("a", DeliveryStatus.SENT, sentAt = 90L)
        recipientDao.rows += row("b", DeliveryStatus.QUEUED)

        aggregator.recompute(MESSAGE_ID)

        assertEquals(DeliveryStatus.SENT, statusOf())
        // The first one on the wire: that is when the message started going out.
        assertEquals(90L, messageDao.getById(MESSAGE_ID)?.sentAtUnixMs)
    }

    @Test
    fun `a failed member does not hold the message back while another is sent`() = runTest {
        seedMessage()
        recipientDao.rows += row("a", DeliveryStatus.SENT, sentAt = 90L)
        recipientDao.rows += row("b", DeliveryStatus.FAILED)

        aggregator.recompute(MESSAGE_ID)

        assertEquals(DeliveryStatus.SENT, statusOf())
    }

    @Test
    fun `failed only when every recipient failed`() = runTest {
        seedMessage()
        recipientDao.rows += row("a", DeliveryStatus.FAILED)
        recipientDao.rows += row("b", DeliveryStatus.QUEUED)

        aggregator.recompute(MESSAGE_ID)
        assertEquals(DeliveryStatus.QUEUED, statusOf())

        recipientDao.markFailed(MESSAGE_ID, "b")
        aggregator.recompute(MESSAGE_ID)

        assertEquals(DeliveryStatus.FAILED, statusOf())
    }

    @Test
    fun `a message with no recipient rows is left untouched`() = runTest {
        seedMessage(status = DeliveryStatus.SENT)
        recipientDao.rows += row("a", DeliveryStatus.READ, readAt = 10L).copy(messageId = "other")

        aggregator.recompute(MESSAGE_ID)

        assertEquals(DeliveryStatus.SENT, statusOf())
        assertNull(messageDao.getById(MESSAGE_ID)?.readAtUnixMs)
    }

    private suspend fun statusOf(): DeliveryStatus? = messageDao.getById(MESSAGE_ID)?.status

    /** The DAO's monotonic update, with the rank the callers in main always pass. */
    private suspend fun advance(
        identityHash: String,
        status: DeliveryStatus,
        deliveredAt: Long? = null,
        readAt: Long? = null,
    ) = recipientDao.advance(
        messageId = MESSAGE_ID,
        identityHash = identityHash,
        status = status,
        rank = status.rank(),
        sentAt = null,
        deliveredAt = deliveredAt,
        readAt = readAt,
    )

    private suspend fun seedMessage(status: DeliveryStatus = DeliveryStatus.QUEUED) {
        messageDao.insert(
            MessageEntity(
                messageId = MESSAGE_ID,
                conversationId = "g1",
                direction = MessageDirection.OUTGOING,
                contentType = MessageContentType.TEXT,
                body = "salam",
                replyToMessageId = null,
                status = status,
                createdAtUnixMs = 1L,
                sentAtUnixMs = null,
                deliveredAtUnixMs = null,
                readAtUnixMs = null,
            ),
        )
    }

    private fun row(
        identityHash: String,
        status: DeliveryStatus,
        sentAt: Long? = null,
        deliveredAt: Long? = null,
        readAt: Long? = null,
    ) = MessageRecipientEntity(
        messageId = MESSAGE_ID,
        identityHash = identityHash,
        status = status,
        sentAtUnixMs = sentAt,
        deliveredAtUnixMs = deliveredAt,
        readAtUnixMs = readAt,
    )

    private companion object {
        const val MESSAGE_ID = "m1"
    }
}
