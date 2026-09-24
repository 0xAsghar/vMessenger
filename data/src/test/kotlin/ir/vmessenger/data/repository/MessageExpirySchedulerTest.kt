package ir.vmessenger.data.repository

import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.data.network.InboundHarness
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageExpirySchedulerTest {
    private val harness = InboundHarness()
    private val scheduler = MessageExpiryScheduler(harness.messageDao, harness.writer)

    @Test
    fun `a timed message is erased at its deadline, not at the next sweep`() = runTest {
        start()
        insert("soon", expiresAt = START + 90_000)
        insert("forever", expiresAt = null)

        passes(89_000)
        assertEquals(setOf("soon", "forever"), ids())
        passes(2_000)

        assertEquals(setOf("forever"), ids())
    }

    @Test
    fun `each purge arms the wait for the next deadline`() = runTest {
        start()
        insert("first", expiresAt = START + 10_000)
        insert("second", expiresAt = START + 5 * 60_000)

        passes(11_000)
        assertEquals(setOf("second"), ids())
        passes(5 * 60_000)

        assertEquals(emptySet<String>(), ids())
    }

    @Test
    fun `a sooner deadline arriving mid-wait cuts the wait short`() = runTest {
        start()
        insert("later", expiresAt = START + 60 * 60_000)
        passes(1_000)

        insert("sooner", expiresAt = START + 30_000)
        passes(30_000)

        assertEquals(setOf("later"), ids())
    }

    @Test
    fun `whatever came due while nothing was running goes at once`() = runTest {
        insert("overdue", expiresAt = START - 1)

        start()

        assertEquals(emptySet<String>(), ids())
    }

    /** The scheduler's clock is the test's virtual one, so waiting costs no real time. */
    private fun TestScope.start() {
        scheduler.clock = { START + testScheduler.currentTime }
        backgroundScope.launch { scheduler.run() }
        runCurrent()
    }

    private fun TestScope.passes(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private suspend fun insert(id: String, expiresAt: Long?) {
        harness.messageDao.insert(
            MessageEntity(
                messageId = id,
                conversationId = "c",
                direction = MessageDirection.INCOMING,
                contentType = MessageContentType.TEXT,
                body = id,
                replyToMessageId = null,
                status = DeliveryStatus.DELIVERED,
                createdAtUnixMs = START,
                sentAtUnixMs = null,
                deliveredAtUnixMs = START,
                readAtUnixMs = null,
                expiresAtUnixMs = expiresAt,
            ),
        )
    }

    private fun ids(): Set<String> = harness.messageDao.messages.mapTo(HashSet()) { it.messageId }

    private companion object {
        const val START = 1_750_000_000_000L
    }
}
