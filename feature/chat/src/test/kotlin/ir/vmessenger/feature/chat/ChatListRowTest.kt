package ir.vmessenger.feature.chat

import ir.vmessenger.core.designsystem.component.DeliveryTicksState
import ir.vmessenger.domain.model.ConversationSummary
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.MessageDirection
import ir.vmessenger.domain.model.MessagePreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatListRowTest {

    @Test
    fun `the user's own last message carries its ticks`() {
        val row = summary(MessagePreviewKind.TEXT, MessageDirection.OUTGOING, DeliveryStatus.QUEUED).toRow()
        assertEquals(DeliveryTicksState.QUEUED, row.ticks)
    }

    @Test
    fun `a system line carries none, though a rename of the user's is stored as theirs`() {
        val row = summary(MessagePreviewKind.GROUP_EVENT, MessageDirection.OUTGOING, DeliveryStatus.QUEUED).toRow()
        assertNull(row.ticks)
    }

    @Test
    fun `an incoming message carries none`() {
        val row = summary(MessagePreviewKind.TEXT, MessageDirection.INCOMING, DeliveryStatus.DELIVERED).toRow()
        assertNull(row.ticks)
    }

    private fun summary(kind: MessagePreviewKind, direction: MessageDirection, status: DeliveryStatus) =
        ConversationSummary(
            id = "g1",
            contactId = null,
            groupId = "g1",
            contactName = "Design review",
            identityHash = ByteArray(0),
            groupAvatarSeed = "seed",
            lastSenderName = null,
            preview = "The group was renamed",
            previewKind = kind,
            lastDirection = direction,
            lastStatus = status,
            lastActivityUnixMs = 0L,
            unreadCount = 0,
            muted = false,
        )
}
