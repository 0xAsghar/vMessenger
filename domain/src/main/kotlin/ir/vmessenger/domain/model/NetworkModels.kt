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
