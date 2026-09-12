package ir.vmessenger.data.repository

import ir.vmessenger.core.database.dao.ChatListRow
import ir.vmessenger.core.database.dao.MessageWithReply
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.domain.model.AttachmentType
import ir.vmessenger.domain.model.ChatAttachment
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.ConversationSummary
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.MessageDirection
import ir.vmessenger.domain.model.MessagePreviewKind
import ir.vmessenger.domain.model.ReplyPreview
import ir.vmessenger.core.database.entity.DeliveryStatus as DbDeliveryStatus
import ir.vmessenger.core.database.entity.MessageDirection as DbMessageDirection

/**
 * Storage rows to domain models for the chat screens. Kept out of
 * [ConversationRepositoryImpl] so the projections can be asserted field by field
 * without standing up the repository's Android-bound collaborators.
 */

internal fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
    messageId = messageId,
    conversationId = conversationId,
    direction = direction.toDomain(),
    text = body.orEmpty(),
    status = status.toDomain(),
    createdAtUnixMs = createdAtUnixMs,
    replyToMessageId = replyToMessageId,
    attachment = toAttachment(),
)

internal fun MessageWithReply.toChatMessage(): ChatMessage =
    message.toChatMessage().copy(replyTo = replyPreview(), lastError = lastError)

internal fun ChatListRow.toSummary(): ConversationSummary = ConversationSummary(
    id = conversationId,
    contactId = contactId,
    contactName = displayName ?: contactId,
    identityHash = identityHash ?: ByteArray(0),
    preview = lastBody ?: lastAttachmentName,
    previewKind = lastContentType?.toPreviewKind(),
    lastDirection = lastDirection?.toDomain(),
    lastStatus = lastStatus?.toDomain(),
    lastActivityUnixMs = lastActivityUnixMs,
    unreadCount = unreadCount,
    muted = muted,
)

/**
 * Null when the message is not a reply, and also when the quoted message is not
 * resolvable (deleted for me, or a peer quoting an id from another chat) — the
 * bubble then renders without a quote rather than with an empty one.
 */
private fun MessageWithReply.replyPreview(): ReplyPreview? {
    val quotedId = message.replyToMessageId
    val kind = replyContentType
    return if (quotedId == null || kind == null) {
        null
    } else {
        ReplyPreview(
            messageId = quotedId,
            senderIsMe = replyDirection == DbMessageDirection.OUTGOING,
            preview = replyBody ?: replyAttachmentName.orEmpty(),
            contentType = kind.toPreviewKind(),
        )
    }
}

private fun MessageEntity.toAttachment(): ChatAttachment? {
    val type = when (contentType) {
        MessageContentType.IMAGE -> AttachmentType.IMAGE
        MessageContentType.VIDEO -> AttachmentType.VIDEO
        MessageContentType.FILE -> AttachmentType.FILE
        else -> null
    }
    return type?.let {
        ChatAttachment(
            type = it,
            fileName = attachmentName ?: "file",
            mimeType = attachmentMimeType ?: "application/octet-stream",
            sizeBytes = attachmentSizeBytes ?: 0L,
            localPath = attachmentPath,
        )
    }
}

private fun DbMessageDirection.toDomain(): MessageDirection = when (this) {
    DbMessageDirection.OUTGOING -> MessageDirection.OUTGOING
    DbMessageDirection.INCOMING -> MessageDirection.INCOMING
}

private fun DbDeliveryStatus.toDomain(): DeliveryStatus = when (this) {
    DbDeliveryStatus.QUEUED -> DeliveryStatus.QUEUED
    DbDeliveryStatus.SENT -> DeliveryStatus.SENT
    DbDeliveryStatus.DELIVERED -> DeliveryStatus.DELIVERED
    DbDeliveryStatus.READ -> DeliveryStatus.READ
    DbDeliveryStatus.FAILED -> DeliveryStatus.FAILED
}

private fun MessageContentType.toPreviewKind(): MessagePreviewKind = when (this) {
    MessageContentType.TEXT -> MessagePreviewKind.TEXT
    MessageContentType.IMAGE -> MessagePreviewKind.IMAGE
    MessageContentType.VIDEO -> MessagePreviewKind.VIDEO
    MessageContentType.FILE -> MessagePreviewKind.FILE
    MessageContentType.LOCATION_CONTROL -> MessagePreviewKind.LOCATION
    MessageContentType.RECEIPT -> MessagePreviewKind.OTHER
}
