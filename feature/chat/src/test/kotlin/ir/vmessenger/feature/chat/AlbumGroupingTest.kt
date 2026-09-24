package ir.vmessenger.feature.chat

import ir.vmessenger.core.designsystem.component.DeliveryTicksState
import ir.vmessenger.domain.model.AttachmentType
import ir.vmessenger.domain.model.ChatAttachment
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.MessageDirection
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which rows of the newest-first window draw as one album grid. */
class AlbumGroupingTest {
    @Test
    fun `an album's images sent together are one run`() {
        val window = listOf(photo("p3", "a", 2), photo("p2", "a", 1), photo("p1", "a", 0))

        assertEquals(3, runAt(window, 0))
    }

    @Test
    fun `something said between two images splits the album`() {
        val window = listOf(photo("p3", "a", 2), text("t"), photo("p2", "a", 1), photo("p1", "a", 0))

        assertEquals(1, runAt(window, 0))
        assertEquals(2, runAt(window, 2))
    }

    @Test
    fun `two albums side by side stay two`() {
        val window = listOf(photo("b2", "b", 1), photo("b1", "b", 0), photo("a2", "a", 1), photo("a1", "a", 0))

        assertEquals(2, runAt(window, 0))
        assertEquals(2, runAt(window, 2))
    }

    @Test
    fun `a photo sent on its own, or deleted for everyone, is not drawn in a grid`() {
        val window = listOf(photo("solo", albumId = null, index = null), photo("p2", "a", 1, deleted = true))

        assertEquals(1, runAt(window, 0))
        assertEquals(1, runAt(window, 1))
    }

    @Test
    fun `the same album id from the other side is not the same album`() {
        val window = listOf(photo("p2", "a", 1), photo("p1", "a", 0, direction = MessageDirection.OUTGOING))

        assertEquals(1, runAt(window, 0))
    }

    @Test
    fun `midnight ends a run, since a day separator is drawn there`() {
        val window = listOf(photo("p2", "a", 1, at = DAY_TWO), photo("p1", "a", 0, at = DAY_TWO - ONE_DAY))

        assertEquals(1, runAt(window, 0))
    }

    @Test
    fun `an album's ticks are its slowest image's, and failure outranks them all`() {
        val read = item("r", DeliveryTicksState.READ)
        val sent = item("s", DeliveryTicksState.SENT)

        assertEquals(DeliveryTicksState.SENT, album(read, sent).ticks)
        assertEquals(DeliveryTicksState.FAILED, album(read, item("f", DeliveryTicksState.FAILED, failed = true)).ticks)
        assertNull(album(item("in1", null), item("in2", null)).ticks)
    }

    @Test
    fun `lookups see through an album to the images inside it`() {
        val grid = album(item("x", null), item("y", null))
        val items = listOf<ChatItem>(item("before", null), grid)

        assertEquals("y", items.message("y")?.messageId)
        assertEquals(listOf("before", "x", "y"), items.messages().map { it.messageId }.toList())
        assertTrue(grid.draws("x"))
        assertFalse(grid.draws("before"))
    }

    private fun runAt(window: List<ChatMessage>, index: Int): Int =
        albumRunLength(window, index, dayKey(window[index].createdAtUnixMs))

    @Suppress("LongParameterList") // one knob per way two photos can differ
    private fun photo(
        id: String,
        albumId: String?,
        index: Int?,
        deleted: Boolean = false,
        direction: MessageDirection = MessageDirection.INCOMING,
        at: Long = DAY_TWO,
    ) = ChatMessage(
        messageId = id,
        conversationId = "c",
        direction = direction,
        text = "",
        status = DeliveryStatus.DELIVERED,
        createdAtUnixMs = at,
        attachment = ChatAttachment(
            type = AttachmentType.IMAGE,
            fileName = "$id.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1L,
            localPath = "/p/$id",
        ),
        deleted = deleted,
        albumId = albumId,
        albumIndex = index,
    )

    private fun text(id: String) = ChatMessage(
        messageId = id,
        conversationId = "c",
        direction = MessageDirection.INCOMING,
        text = "between",
        status = DeliveryStatus.DELIVERED,
        createdAtUnixMs = DAY_TWO,
    )

    private fun item(id: String, ticks: DeliveryTicksState?, failed: Boolean = false) = ChatItem.Message(
        messageId = id,
        outgoing = ticks != null,
        text = "",
        time = "",
        ticks = ticks,
        attachment = null,
        reply = null,
        failed = failed,
        errorCode = null,
    )

    private fun album(vararg images: ChatItem.Message) = ChatItem.Album(
        images = persistentListOf(*images),
        anchorMessageId = images.first().messageId,
        newestMessageId = images.last().messageId,
        startsSenderRun = false,
    )

    private companion object {
        const val ONE_DAY = 24 * 60 * 60 * 1000L

        /** Noon, so a day either side is a different calendar day in any zone the tests run in. */
        const val DAY_TWO = 1_750_075_200_000L
    }
}
