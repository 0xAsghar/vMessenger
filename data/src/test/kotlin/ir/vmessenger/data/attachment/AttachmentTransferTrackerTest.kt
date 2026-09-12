package ir.vmessenger.data.attachment

import ir.vmessenger.domain.model.MessageDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live transfer progress, per contact.
 *
 * The empty-set case is the one that bit: the conversation screen `combine`s this flow with
 * everything else it renders, so a version that emitted nothing for "no contacts to track"
 * held the whole screen at its initial state — a group whose last other member had left came
 * out blank, with no title, no messages and no error.
 */
class AttachmentTransferTrackerTest {
    private val tracker = AttachmentTransferTracker()

    @Test
    fun `a tracker with no contacts still emits`() = runTest {
        assertTrue(tracker.forContacts(emptySet()).first().isEmpty())
    }

    @Test
    fun `progress is reported for the contacts asked for and nobody else`() = runTest {
        tracker.update("m-1", "a", bytesDone = 5, totalBytes = 10, direction = MessageDirection.OUTGOING)
        tracker.update("m-2", "b", bytesDone = 1, totalBytes = 10, direction = MessageDirection.OUTGOING)

        assertEquals(setOf("m-1"), tracker.forContacts(setOf("a")).first().keys)
        assertEquals(setOf("m-1", "m-2"), tracker.forContacts(setOf("a", "b")).first().keys)
    }

    @Test
    fun `a completed transfer leaves the map`() = runTest {
        tracker.update("m-1", "a", bytesDone = 10, totalBytes = 10, direction = MessageDirection.INCOMING)

        tracker.remove("m-1")

        assertTrue(tracker.forContacts(setOf("a")).first().isEmpty())
    }
}
