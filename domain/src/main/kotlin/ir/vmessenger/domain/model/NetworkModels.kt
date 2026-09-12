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
    val replyToMessageId: String? = null,
    val attachment: ChatAttachment? = null,
    /** Preview of the quoted message, resolved by the DAO's reply JOIN; null when not a reply. */
    val replyTo: ReplyPreview? = null,
    /**
     * Why the last delivery attempt failed, straight from the outbox row (an
     * [ir.vmessenger.core.common.AppError] message, e.g. the peer's protocol
     * major). Null once the message is delivered or was never attempted.
     */
    val lastError: String? = null,
)

/** How a message renders in a one-line preview (chat list row, reply quote). */
enum class MessagePreviewKind {
    TEXT,
    IMAGE,
    VIDEO,
    FILE,
    LOCATION,
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

enum class AttachmentType { IMAGE, VIDEO, FILE }

data class ChatAttachment(
    val type: AttachmentType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Absolute path in app-private storage; null while an incoming transfer is pending. */
    val localPath: String?,
)

enum class MessageDirection {
    OUTGOING,
    INCOMING,
}

data class Conversation(
    val id: String,
    val contactId: String,
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
