package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.AttachmentChunk
import ir.vmessenger.core.proto.app.v1.AttachmentInfo
import ir.vmessenger.core.proto.app.v1.AttachmentKind
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams an attachment to a peer as an [AttachmentInfo] header followed by
 * sequential [AttachmentChunk] envelopes over the same secure session. The
 * receiver materializes the chat message once every chunk has arrived; a
 * failed transfer is retried from scratch by the outbox (the header resets
 * any partial state on the receiving side).
 */
@Singleton
class AttachmentSender @Inject constructor(
    private val messagingService: MessagingService,
) {
    @Suppress("ReturnCount")
    suspend fun send(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        message: MessageEntity,
        file: File,
    ): AppResult<Unit> {
        val size = file.length()
        val chunkCount = ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        val transferId = ByteString.copyFromUtf8(message.messageId)
        val header = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8(message.messageId))
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(message.createdAtUnixMs)
            .setCounter(1)
            .setAttachmentInfo(
                AttachmentInfo.newBuilder()
                    .setTransferId(transferId)
                    .setFileName(message.attachmentName.orEmpty())
                    .setMimeType(message.attachmentMimeType.orEmpty())
                    .setTotalSize(size)
                    .setChunkCount(chunkCount)
                    .setKind(message.contentType.toKind()),
            )
            .build()
        when (val result = messagingService.send(contactId, self, peer, header)) {
            is AppResult.Success -> Unit
            is AppResult.Error -> return result
        }
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            var index = 0
            while (true) {
                val read = readFully(input, buffer)
                if (read <= 0) break
                val chunkEnvelope = MessageEnvelope.newBuilder()
                    .setMessageId(ByteString.copyFromUtf8("${message.messageId}-c$index"))
                    .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
                    .setSentAtUnixMs(System.currentTimeMillis())
                    .setCounter(1)
                    .setAttachmentChunk(
                        AttachmentChunk.newBuilder()
                            .setTransferId(transferId)
                            .setIndex(index)
                            .setData(ByteString.copyFrom(buffer, 0, read)),
                    )
                    .build()
                when (val result = messagingService.send(contactId, self, peer, chunkEnvelope)) {
                    is AppResult.Success -> Unit
                    is AppResult.Error -> {
                        AppLogger.warn(
                            "Attachment",
                            "chunk $index/${chunkCount - 1} failed for ${message.messageId}: ${result.error.message}",
                        )
                        return result
                    }
                }
                index++
            }
        }
        AppLogger.info("Attachment", "sent ${message.messageId} ($chunkCount chunk(s), $size bytes)")
        return AppResult.Success(Unit)
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    private fun MessageContentType.toKind(): AttachmentKind = when (this) {
        MessageContentType.IMAGE -> AttachmentKind.ATTACHMENT_KIND_IMAGE
        MessageContentType.VIDEO -> AttachmentKind.ATTACHMENT_KIND_VIDEO
        else -> AttachmentKind.ATTACHMENT_KIND_FILE
    }

    companion object {
        // Comfortably below the 1 MiB transport frame cap after proto + AEAD overhead.
        const val CHUNK_BYTES = 128 * 1024
    }
}
