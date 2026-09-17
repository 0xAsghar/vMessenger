package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.MessageDelete
import ir.vmessenger.core.proto.app.v1.MessageEdit
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.attachment.AttachmentFileStore
import ir.vmessenger.data.repository.ConversationWriter
import ir.vmessenger.data.repository.MessageRecipientResolver
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Revising a message we sent: a new body, or a request that everyone drop it.
 *
 * Both go out through the per-recipient outbox with a pre-built envelope, the way group controls
 * do, so an offline peer still gets them when they return. A direct send would have reached only
 * whoever happened to be online at that moment, which for an edit is the case that matters least.
 *
 * The local copy is changed first and unconditionally. Delivery is best-effort by nature — a peer
 * can ignore a delete, and nothing in an end-to-end encrypted network can make them comply — so
 * the user's own history must not wait on it.
 */
@Singleton
class MessageRevisionSender @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val recipients: MessageRecipientResolver,
    private val writer: ConversationWriter,
    private val attachmentFiles: AttachmentFileStore,
) {
    suspend fun edit(messageId: String, newText: String): AppResult<Unit> {
        val trimmed = newText.trim()
        val message = editable(messageId).takeIf { trimmed.isNotBlank() }
            ?: return AppResult.Error(AppError.Validation(if (trimmed.isBlank()) EMPTY_TEXT else NOT_EDITABLE))
        val now = System.currentTimeMillis()
        val revised = if (message.contentType == MessageContentType.TEXT) {
            message.copy(body = trimmed, editedAtUnixMs = now)
        } else {
            message.copy(caption = trimmed, editedAtUnixMs = now)
        }
        messageDao.update(revised)
        fanOut(message.conversationId) {
            setMessageEdit(
                MessageEdit.newBuilder()
                    .setTargetMessageId(ByteString.copyFromUtf8(messageId))
                    .setNewText(trimmed)
                    .setEditedAtUnixMs(now),
            )
        }
        return AppResult.Success(Unit)
    }

    suspend fun deleteForEveryone(messageId: String): AppResult<Unit> {
        val message = messageDao.getById(messageId)
            ?.takeIf { it.direction == MessageDirection.OUTGOING }
            ?: return AppResult.Error(AppError.Validation(NOT_OURS))
        val now = System.currentTimeMillis()
        message.attachmentPath?.let(attachmentFiles::delete)
        messageDao.update(tombstone(message, deletedAtUnixMs = now))
        fanOut(message.conversationId) {
            setMessageDelete(
                MessageDelete.newBuilder()
                    .setTargetMessageId(ByteString.copyFromUtf8(messageId))
                    .setDeletedAtUnixMs(now),
            )
        }
        return AppResult.Success(Unit)
    }

    /** Only our own text, and never a tombstone or a system line. */
    private suspend fun editable(messageId: String): MessageEntity? =
        messageDao.getById(messageId)?.takeIf {
            it.direction == MessageDirection.OUTGOING &&
                (it.contentType == MessageContentType.TEXT || it.caption != null)
        }

    private fun tombstone(message: MessageEntity, deletedAtUnixMs: Long) = message.copy(
        contentType = MessageContentType.DELETED,
        deletedAtUnixMs = deletedAtUnixMs,
        body = null,
        caption = null,
        replyToMessageId = null,
        attachmentName = null,
        attachmentMimeType = null,
        attachmentSizeBytes = null,
        attachmentPath = null,
        attachmentSha256 = null,
        attachmentWaveform = null,
        attachmentDurationMs = null,
    )

    private suspend fun fanOut(conversationId: String, content: MessageEnvelope.Builder.() -> Unit) {
        val targets = recipients.resolve(conversationId)
        if (targets.isEmpty()) {
            // The local change already happened; there is simply nobody left to tell.
            AppLogger.info("Messaging", "revision has no reachable recipient conversation=$conversationId")
            return
        }
        val self = conversationDao.getById(conversationId)
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
            .setSentAtUnixMs(System.currentTimeMillis())
            .apply { self?.groupId?.let { setGroupId(ByteString.copyFromUtf8(it)) } }
            .apply(content)
            .build()
        writer.queueMessageControl(conversationId, envelope.toByteArray(), targets)
    }

    private companion object {
        const val NOT_EDITABLE = "این پیام قابل ویرایش نیست"
        const val EMPTY_TEXT = "متن پیام نمی‌تواند خالی باشد"
        const val NOT_OURS = "تنها پیام‌های خودتان را می‌توانید حذف کنید"
    }
}
