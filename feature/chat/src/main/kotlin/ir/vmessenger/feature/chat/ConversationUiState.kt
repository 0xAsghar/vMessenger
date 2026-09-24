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

/** Conversation title block: who this is, and the states worth warning about. */
@Immutable
data class ConversationHeaderUi(
    val title: String = "",
    val seed: IdentitySeed = IdentitySeed.Empty,
    val contactId: String? = null,
    /** Set for a group thread; exactly one of this and [contactId] is non-null. */
    val groupId: String? = null,
    /** The group's members as one line, the way every messenger writes a group subtitle. */
    val memberNames: String? = null,
    /** A closed group is history: readable, but nothing more can be sent. */
    val closed: Boolean = false,
    /**
     * The creator has stopped answering this group's snapshot requests, so its
     * membership is stuck at whatever we last saw.
     */
    val outOfSync: Boolean = false,
    val verified: Boolean = false,
    val keyChangePending: Boolean = false,
    val blocked: Boolean = false,
) {
    val isGroup: Boolean get() = groupId != null
}

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
    /** Voice only: its length, its 64-bar waveform, and whether anyone has listened yet. */
    val durationMs: Long? = null,
    val waveform: ByteArray? = null,
    val unplayed: Boolean = false,
) {
    // Written out because of the waveform: a data class would compare the array by identity,
    // and every database emission would then look like a change and recompose every bubble.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AttachmentUi
        return scalarFields() == other.scalarFields() &&
            (waveform ?: EMPTY).contentEquals(other.waveform ?: EMPTY)
    }

    override fun hashCode(): Int = 31 * scalarFields().hashCode() + (waveform?.contentHashCode() ?: 0)

    private fun scalarFields(): List<Any?> =
        listOf(type, fileName, mimeType, sizeBytes, available, durationMs, unplayed)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

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

    /** A membership change: a centred line, not a bubble, and not long-pressable. */
    @Immutable
    data class System(val messageId: String, val text: String) : ChatItem {
        override val key: String get() = messageId
        override val contentType: String get() = "system"
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
        /** Who sent this, in a group; null in a 1:1 chat and for our own messages. */
        val senderName: String? = null,
        /** Picks the sender's name colour and their avatar; null when no sender is shown. */
        val senderSeed: IdentitySeed? = null,
        /**
         * True only for the first message of a run by the same sender, which is where the
         * name and the avatar go — repeating them on every bubble is noise.
         */
        val startsSenderRun: Boolean = false,
        /** Marks the bubble as revised since it was sent. */
        val edited: Boolean = false,
        /** The sender asked everyone to drop it; the row survives so replies to it still resolve. */
        val deleted: Boolean = false,
        /** The message self-destructs; the bubble shows a timer glyph. */
        val expiring: Boolean = false,
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

    /**
     * Images sent together, drawn as one grid rather than a column of separate bubbles.
     *
     * [images] are in the order they were picked; each keeps its own row, transfer, failure and
     * long-press, so the grid is only a way of drawing them. What the bubble says about itself
     * comes from them: the time is the newest arrival's, the ticks the least advanced.
     */
    @Immutable
    data class Album(
        val images: ImmutableList<Message>,
        /** The run's oldest row, which stays put as later images land: a stable list key. */
        val anchorMessageId: String,
        /** The run's newest row: what the list follows as images arrive. */
        val newestMessageId: String,
        /** In a group, whether the sender's name goes on this bubble; see [Message.startsSenderRun]. */
        val startsSenderRun: Boolean,
    ) : ChatItem {
        override val key: String get() = "album-$anchorMessageId"
        override val contentType: String get() = "msg-album"

        val first: Message get() = images.first()
        val newest: Message get() = images.first { it.messageId == newestMessageId }

        /** Failed if any image failed; otherwise no further along than the slowest image. */
        val ticks: DeliveryTicksState?
            get() = if (images.any { it.failed }) {
                DeliveryTicksState.FAILED
            } else {
                images.mapNotNull { it.ticks }.minByOrNull { it.ordinal }
            }

        operator fun contains(messageId: String): Boolean = images.any { it.messageId == messageId }
    }
}

/** Every message on screen, an album's images included, in list order. */
internal fun List<ChatItem>.messages(): Sequence<ChatItem.Message> = asSequence().flatMap { item ->
    when (item) {
        is ChatItem.Message -> sequenceOf(item)
        is ChatItem.Album -> item.images.asSequence()
        is ChatItem.Day, is ChatItem.System -> emptySequence()
    }
}

/** The message with this id, wherever it is drawn; null when it is not on screen. */
internal fun List<ChatItem>.message(messageId: String): ChatItem.Message? =
    messages().firstOrNull { it.messageId == messageId }

/** Whether this row draws the message: the row itself, or the album it is part of. */
internal fun ChatItem.draws(messageId: String): Boolean = when (this) {
    is ChatItem.Album -> messageId in this
    else -> key == messageId
}

/** The newest message a row draws, for following the list as messages arrive. */
internal val ChatItem.newestMessageId: String?
    get() = when (this) {
        is ChatItem.Message -> messageId
        is ChatItem.Album -> newestMessageId
        is ChatItem.Day, is ChatItem.System -> null
    }

/** Composer state the screen owns: draft text, the message being replied to, and whether sending is allowed. */
@Immutable
data class ComposerUiState(
    val text: String = "",
    val enabled: Boolean = true,
    val replyTo: ReplyQuoteUi? = null,
    /** Set while the composer is revising a sent message rather than writing a new one. */
    val editingMessageId: String? = null,
    /** When new messages in this chat erase themselves; null when they do not. */
    val timer: MessageTimer? = null,
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
    /**
     * The message a reply quote was just tapped through to. Held for a couple of seconds after
     * the scroll lands so the target is findable — scrolling alone drops the user somewhere in a
     * wall of bubbles with no clue which one they asked for.
     */
    val highlightedMessageId: String? = null,
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
