package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.AttachmentInfo
import ir.vmessenger.core.proto.app.v1.AttachmentKind
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.attachment.AttachmentStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A fully received attachment ready for notification + delivery receipt. */
data class CompletedAttachment(
    val contactId: String,
    val messageId: String,
    val fileName: String,
)

/**
 * Reassembles incoming attachment transfers (header + ordered chunks) into
 * app-private files and materializes the chat message on completion. A
 * repeated header for the same transfer resets partial state, so sender-side
 * retries are safe.
 */
@Singleton
class AttachmentReceiver @Inject constructor(
    private val attachmentStore: AttachmentStore,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) {
    @Suppress("LongParameterList")
    private class Pending(
        val contactId: String,
        val messageId: String,
        val info: AttachmentInfo,
        val file: File,
        val output: FileOutputStream,
        var nextIndex: Int = 0,
        var receivedBytes: Long = 0,
        val startedAtUnixMs: Long = System.currentTimeMillis(),
    )

    private val pending = mutableMapOf<String, Pending>()
    private val lock = Any()

    @Suppress("ReturnCount")
    suspend fun handleInfo(contactId: String, envelope: MessageEnvelope): Boolean {
        val info = envelope.attachmentInfo
        val messageId = envelope.messageId.toStringUtf8()
        val key = transferKey(info.transferId.toByteArray())
        if (messageDao.getById(messageId) != null) {
            // Already delivered previously; ask the caller to re-send the receipt.
            AppLogger.info("Attachment", "duplicate transfer for delivered messageId=$messageId")
            return true
        }
        if (!isAcceptable(info)) {
            AppLogger.warn(
                "Attachment",
                "rejected transfer size=${info.totalSize} chunks=${info.chunkCount} from contact=$contactId",
            )
            return false
        }
        val fileName = info.fileName.ifBlank { "attachment" }
        val target = attachmentStore.newIncomingFile(fileName)
        synchronized(lock) {
            prune()
            pending.remove(key)?.closeQuietly()
            pending[key] = Pending(
                contactId = contactId,
                messageId = messageId,
                info = info,
                file = target,
                output = FileOutputStream(target),
            )
        }
        AppLogger.info(
            "Attachment",
            "transfer started messageId=$messageId size=${info.totalSize} chunks=${info.chunkCount}",
        )
        return false
    }

    @Suppress("ReturnCount")
    suspend fun handleChunk(contactId: String, envelope: MessageEnvelope): CompletedAttachment? {
        val chunk = envelope.attachmentChunk
        val key = transferKey(chunk.transferId.toByteArray())
        val transfer: Pending
        val complete: Boolean
        synchronized(lock) {
            transfer = pending[key] ?: return null
            if (transfer.contactId != contactId || chunk.index != transfer.nextIndex) {
                AppLogger.warn(
                    "Attachment",
                    "out-of-order chunk ${chunk.index} (expected ${transfer.nextIndex}); dropping transfer",
                )
                pending.remove(key)?.closeQuietly()
                return null
            }
            val data = chunk.data.toByteArray()
            transfer.receivedBytes += data.size
            if (transfer.receivedBytes > transfer.info.totalSize) {
                AppLogger.warn("Attachment", "transfer exceeded declared size; dropping")
                pending.remove(key)?.closeQuietly()
                return null
            }
            transfer.output.write(data)
            transfer.nextIndex++
            complete = transfer.nextIndex >= transfer.info.chunkCount
            if (complete) {
                transfer.output.close()
                pending.remove(key)
            }
        }
        if (!complete) return null
        return materialize(transfer)
    }

    private suspend fun materialize(transfer: Pending): CompletedAttachment? {
        val info = transfer.info
        val now = System.currentTimeMillis()
        val conversationId = conversationDao.getByContactId(transfer.contactId)?.id
            ?: UUID.randomUUID().toString().also { id ->
                conversationDao.upsert(
                    ConversationEntity(
                        id = id,
                        contactId = transfer.contactId,
                        lastMessageId = transfer.messageId,
                        lastActivityUnixMs = now,
                        unreadCount = 0,
                        muted = false,
                    ),
                )
            }
        messageDao.insert(
            MessageEntity(
                messageId = transfer.messageId,
                conversationId = conversationId,
                direction = MessageDirection.INCOMING,
                contentType = info.kind.toContentType(),
                body = null,
                replyToMessageId = null,
                status = DeliveryStatus.DELIVERED,
                createdAtUnixMs = now,
                sentAtUnixMs = null,
                deliveredAtUnixMs = now,
                readAtUnixMs = null,
                attachmentName = info.fileName.ifBlank { transfer.file.name },
                attachmentMimeType = info.mimeType,
                attachmentSizeBytes = transfer.receivedBytes,
                attachmentPath = transfer.file.absolutePath,
            ),
        )
        conversationDao.getById(conversationId)?.let { conv ->
            conversationDao.update(
                conv.copy(
                    lastMessageId = transfer.messageId,
                    lastActivityUnixMs = now,
                    unreadCount = conv.unreadCount + 1,
                ),
            )
        }
        AppLogger.info(
            "Attachment",
            "transfer complete messageId=${transfer.messageId} bytes=${transfer.receivedBytes}",
        )
        return CompletedAttachment(
            contactId = transfer.contactId,
            messageId = transfer.messageId,
            fileName = info.fileName.ifBlank { transfer.file.name },
        )
    }

    private fun isAcceptable(info: AttachmentInfo): Boolean {
        val maxChunks = (AttachmentStore.MAX_ATTACHMENT_BYTES / AttachmentSender.CHUNK_BYTES + 1).toInt()
        return info.totalSize in 1..AttachmentStore.MAX_ATTACHMENT_BYTES &&
            info.chunkCount in 1..maxChunks
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - STALE_TRANSFER_MS
        val stale = pending.filterValues { it.startedAtUnixMs < cutoff }.keys.toList()
        for (key in stale) {
            pending.remove(key)?.closeQuietly()
        }
    }

    private fun Pending.closeQuietly() {
        runCatching { output.close() }
        runCatching { file.delete() }
    }

    private fun transferKey(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }

    private fun AttachmentKind.toContentType() = when (this) {
        AttachmentKind.ATTACHMENT_KIND_IMAGE -> ir.vmessenger.core.database.entity.MessageContentType.IMAGE
        AttachmentKind.ATTACHMENT_KIND_VIDEO -> ir.vmessenger.core.database.entity.MessageContentType.VIDEO
        else -> ir.vmessenger.core.database.entity.MessageContentType.FILE
    }

    companion object {
        private const val STALE_TRANSFER_MS = 10 * 60_000L
    }
}
