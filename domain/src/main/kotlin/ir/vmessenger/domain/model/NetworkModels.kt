package ir.vmessenger.domain.model

enum class DeliveryStatus {
    QUEUED,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}

data class ChatMessage(
    val messageId: String,
    val conversationId: String,
    val direction: MessageDirection,
    val text: String,
    val status: DeliveryStatus,
    val createdAtUnixMs: Long,
    /**
     * When delivery was actually confirmed — the moment the first tick appears — as opposed to
     * [createdAtUnixMs], which is when the composer queued it. Null while a message is still
     * queued or failed, and for anything predating per-recipient fan-out, so callers must fall
     * back rather than render an empty slot.
     *
     * For an incoming message this is instead the *sender's* clock, clamped on arrival.
     */
    val sentAtUnixMs: Long? = null,
    val deliveredAtUnixMs: Long? = null,
    val readAtUnixMs: Long? = null,
    val replyToMessageId: String? = null,
    val attachment: ChatAttachment? = null,
    /** Preview of the quoted message, resolved by the DAO's reply JOIN; null when not a reply. */
    val replyTo: ReplyPreview? = null,
    /**
     * Who sent an incoming group message, and their lowercase-hex identity hash
     * (which picks their name colour). Both null in 1:1 chats and for our own
     * messages, where the direction already says who sent it.
     */
    val senderName: String? = null,
    val senderIdentityHash: String? = null,
    /** A membership change, rendered as a centred system line rather than a bubble. */
    val isSystemEvent: Boolean = false,
    /** When the sender last revised the text; null for a message never edited. */
    val editedAtUnixMs: Long? = null,
    /** The sender asked everyone to delete it. The row stays so reply quotes still resolve. */
    val deleted: Boolean = false,
    /**
     * Why the last delivery attempt failed, straight from the outbox row (an
     * [ir.vmessenger.core.common.AppError] message, e.g. the peer's protocol
     * major). Null once the message is delivered or was never attempted.
     */
    val lastError: String? = null,
    /** Absolute UTC time a self-destructing message vanishes; null for a message with no timer. */
    val expiresAtUnixMs: Long? = null,
)

/** How a message renders in a one-line preview (chat list row, reply quote). */
enum class MessagePreviewKind {
    TEXT,

    /** The sender asked everyone to delete it; the row survives as a tombstone. */
    DELETED,
    IMAGE,
    VIDEO,
    FILE,
    AUDIO,
    LOCATION,

    /** A membership change, rendered as a centred system line rather than a bubble. */
    GROUP_EVENT,
    OTHER,
}

/**
 * The quoted message shown above a reply bubble. [senderIsMe] decides the
 * "You"/contact-name label without needing the quoted message itself.
 */
data class ReplyPreview(
    val messageId: String,
    val senderIsMe: Boolean,
    val preview: String,
    val contentType: MessagePreviewKind,
)

enum class AttachmentType { IMAGE, VIDEO, FILE, AUDIO }

data class ChatAttachment(
    val type: AttachmentType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Absolute path in app-private storage; null while an incoming transfer is pending. */
    val localPath: String?,
    /** Voice/video length; known before the file arrives because it travels in the transfer header. */
    val durationMs: Long? = null,
    /** 64 amplitude buckets (0..255) drawn as the voice waveform; null for other kinds. */
    val waveform: ByteArray? = null,
    /** False on a voice message nobody has listened to yet, which is what the unplayed dot means. */
    val played: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChatAttachment
        return scalarFields() == other.scalarFields() &&
            (waveform ?: EMPTY).contentEquals(other.waveform ?: EMPTY)
    }

    override fun hashCode(): Int = 31 * scalarFields().hashCode() + (waveform?.contentHashCode() ?: 0)

    /** Every field except the byte array, so equality/hash keep data-class semantics. */
    private fun scalarFields(): List<Any?> =
        listOf(type, fileName, mimeType, sizeBytes, localPath, durationMs, played)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

enum class MessageDirection {
    OUTGOING,
    INCOMING,
}

data class Conversation(
    val id: String,
    val contactId: String?,
    val contactName: String,
    val lastMessagePreview: String?,
    val lastActivityUnixMs: Long,
    val unreadCount: Int,
)

data class LocationSample(
    val shareId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val sampledAtUnixMs: Long,
)
