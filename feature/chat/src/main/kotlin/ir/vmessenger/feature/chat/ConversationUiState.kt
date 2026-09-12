package ir.vmessenger.feature.chat

import androidx.compose.runtime.Immutable
import ir.vmessenger.core.designsystem.component.DeliveryTicksState
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.AttachmentType
import ir.vmessenger.domain.model.MessagePreviewKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/** Conversation title block: who this is, and the two states worth warning about. */
@Immutable
data class ConversationHeaderUi(
    val title: String = "",
    val seed: IdentitySeed = IdentitySeed.Empty,
    val contactId: String? = null,
    val verified: Boolean = false,
    val keyChangePending: Boolean = false,
    val blocked: Boolean = false,
)

/** The quoted message above a reply bubble, and inside the composer's reply strip. */
@Immutable
data class ReplyQuoteUi(
    val messageId: String,
    val senderIsMe: Boolean,
    val preview: String,
    val kind: MessagePreviewKind,
)

/** An attachment as the bubble needs it; [available] is false while an incoming transfer runs. */
@Immutable
data class AttachmentUi(
    val type: AttachmentType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val available: Boolean,
)

/**
 * A row of the message list. Day separators are items too, so the list the LazyColumn
 * renders is exactly the list the ViewModel built — no grouping logic in composition.
 */
@Immutable
sealed interface ChatItem {
    val key: String
    val contentType: String

    @Immutable
    data class Day(val dayKey: Int, val label: String) : ChatItem {
        override val key: String get() = "day-$dayKey"
        override val contentType: String get() = "day"
    }

    @Immutable
    data class Message(
        val messageId: String,
        val outgoing: Boolean,
        val text: String,
        val time: String,
        val ticks: DeliveryTicksState?,
        val attachment: AttachmentUi?,
        val reply: ReplyQuoteUi?,
        val failed: Boolean,
        val errorCode: String?,
    ) : ChatItem {
        override val key: String get() = messageId
        override val contentType: String
            get() = when (attachment?.type) {
                AttachmentType.IMAGE -> "msg-image"
                AttachmentType.AUDIO -> "msg-voice"
                AttachmentType.VIDEO, AttachmentType.FILE -> "msg-file"
                null -> "msg-text"
            }
    }
}

/** Composer state the screen owns: draft text, the message being replied to, and whether sending is allowed. */
@Immutable
data class ComposerUiState(
    val text: String = "",
    val enabled: Boolean = true,
    val replyTo: ReplyQuoteUi? = null,
)

@Immutable
data class ConversationUiState(
    val header: ConversationHeaderUi = ConversationHeaderUi(),
    val items: ImmutableList<ChatItem> = persistentListOf(),
    /** Attachments arriving that have no message row yet; rendered as placeholder bubbles. */
    val pendingIncoming: ImmutableList<String> = persistentListOf(),
    val attachmentProgress: ImmutableMap<String, AttachmentProgress> = persistentMapOf(),
    val composer: ComposerUiState = ComposerUiState(),
    val hasMore: Boolean = false,
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = !loading && items.isEmpty() && pendingIncoming.isEmpty()
}

/** Preview kind of a bubble, used when it is quoted by a reply. */
internal fun AttachmentUi?.previewKind(): MessagePreviewKind = when (this?.type) {
    AttachmentType.IMAGE -> MessagePreviewKind.IMAGE
    AttachmentType.VIDEO -> MessagePreviewKind.VIDEO
    AttachmentType.FILE -> MessagePreviewKind.FILE
    AttachmentType.AUDIO -> MessagePreviewKind.AUDIO
    null -> MessagePreviewKind.TEXT
}
