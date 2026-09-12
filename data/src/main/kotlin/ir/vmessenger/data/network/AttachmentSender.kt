package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.AttachmentChunk
import ir.vmessenger.core.proto.app.v1.AttachmentInfo
import ir.vmessenger.core.proto.app.v1.AttachmentKind
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.attachment.AttachmentContentSource
import ir.vmessenger.data.attachment.AttachmentTransferTracker
import ir.vmessenger.domain.model.MessageDirection
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** The batch-send slice of [MessagingService] the attachment sender needs (fakeable in tests). */
interface AttachmentBatchSender {
    suspend fun sendBatch(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelopes: Sequence<MessageEnvelope>,
        onSent: (Int) -> Unit,
    ): AppResult<Unit>
}

@Singleton
class MessagingBatchSender @Inject constructor(
    private val messagingService: MessagingService,
) : AttachmentBatchSender {
    override suspend fun sendBatch(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelopes: Sequence<MessageEnvelope>,
        onSent: (Int) -> Unit,
    ): AppResult<Unit> = messagingService.sendBatch(contactId, self, peer, envelopes, onSent)
}

/**
 * Outcome of one transfer attempt. [failedIndex] is the batch index that did not
 * get written (0 = header, `i` = chunk `i - 1`), so a caller resuming later
 * knows where the receiver's state stops.
 */
data class AttachmentSendResult(
    val result: AppResult<Unit>,
    val sentCount: Int,
    val chunkCount: Int,
    val failedIndex: Int? = null,
)

/**
 * Streams an attachment to a peer as an [AttachmentInfo] header followed by
 * [AttachmentChunk] envelopes, all over ONE secure session (one handshake per
 * transfer, via [AttachmentBatchSender.sendBatch]). The header carries the
 * plaintext SHA-256; the receiver materializes the message once every chunk
 * arrived and the digest matches. A failed transfer is retried from scratch by
 * the outbox (a repeated header resets the receiver's partial state).
 */
@Singleton
class AttachmentSender @Inject constructor(
    private val batchSender: AttachmentBatchSender,
    private val contentSource: AttachmentContentSource,
    private val tracker: AttachmentTransferTracker,
) {
    suspend fun send(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        message: MessageEntity,
        file: File,
    ): AppResult<Unit> = sendTransfer(contactId, self, peer, message, file).result

    @Suppress("ReturnCount") // two local-read failures exit before the network is touched
    suspend fun sendTransfer(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        message: MessageEntity,
        file: File,
    ): AttachmentSendResult {
        val plan = runCatching { plan(message, file) }.getOrElse { failure ->
            AppLogger.warn("Attachment", "cannot read ${message.messageId}: ${failure.message}")
            return readFailure(chunkCount = 0)
        }
        val input = runCatching { contentSource.openDecrypted(file.absolutePath) }.getOrElse { failure ->
            AppLogger.warn("Attachment", "cannot open ${message.messageId}: ${failure.message}")
            return readFailure(plan.chunkCount)
        }
        val chunks = ChunkReader(input, plan)
        var sent = 0
        tracker.update(message.messageId, contactId, 0L, plan.size, MessageDirection.OUTGOING)
        val result = try {
            batchSender.sendBatch(contactId, self, peer, envelopes(message, self, plan, chunks)) { index ->
                sent = index + 1
                val done = if (index == 0) 0L else minOf(index.toLong() * CHUNK_BYTES, plan.size)
                tracker.update(message.messageId, contactId, done, plan.size, MessageDirection.OUTGOING)
            }
        } finally {
            runCatching { input.close() }
            tracker.remove(message.messageId)
        }
        return finish(message, plan, chunks, result, sent)
    }

    private fun finish(
        message: MessageEntity,
        plan: TransferPlan,
        chunks: ChunkReader,
        result: AppResult<Unit>,
        sent: Int,
    ): AttachmentSendResult {
        val expected = plan.chunkCount + 1
        return when {
            result is AppResult.Error -> {
                AppLogger.warn(
                    "Attachment",
                    "batch index $sent/${expected - 1} failed for ${message.messageId}: ${result.error.message}",
                )
                AttachmentSendResult(result, sent, plan.chunkCount, failedIndex = sent)
            }
            chunks.corrupt || sent != expected -> {
                AppLogger.warn("Attachment", "file changed while sending ${message.messageId} (sent $sent/$expected)")
                readFailure(plan.chunkCount, sent)
            }
            else -> {
                AppLogger.info(
                    "Attachment",
                    "sent ${message.messageId} (${plan.chunkCount} chunk(s), ${plan.size} bytes)",
                )
                AttachmentSendResult(AppResult.Success(Unit), sent, plan.chunkCount)
            }
        }
    }

    private fun readFailure(chunkCount: Int, sent: Int = 0) = AttachmentSendResult(
        result = AppResult.Error(AppError.Validation(READ_FAILED_MESSAGE)),
        sentCount = sent,
        chunkCount = chunkCount,
        failedIndex = sent,
    )

    private class TransferPlan(val size: Long, val chunkCount: Int, val sha256: ByteArray)

    /** Reads plaintext chunks lazily; [corrupt] flips when the file is shorter than the plan says. */
    private class ChunkReader(private val input: InputStream, private val plan: TransferPlan) {
        var corrupt = false
            private set

        fun next(index: Int): ByteArray? {
            val expected = plan.chunkSize(index)
            val buffer = ByteArray(expected)
            if (readFully(input, buffer) != expected) {
                corrupt = true
                return null
            }
            return buffer
        }
    }

    /** Plaintext size and digest: taken from the message row, or computed in one extra pass when missing. */
    private suspend fun plan(message: MessageEntity, file: File): TransferPlan {
        val knownSize = message.attachmentSizeBytes
        val knownDigest = message.attachmentSha256?.takeIf { it.size == SHA256_BYTES }
        val (size, digest) = if (knownSize != null && knownSize > 0 && knownDigest != null) {
            knownSize to knownDigest
        } else {
            measure(contentSource.openDecrypted(file.absolutePath))
        }
        val chunkCount = ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        require(size > 0 && chunkCount > 0) { "empty attachment" }
        return TransferPlan(size, chunkCount, digest)
    }

    private fun measure(input: InputStream): Pair<Long, ByteArray> = input.use {
        val md = MessageDigest.getInstance("SHA-256")
        val stream = DigestInputStream(it, md)
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
        }
        total to md.digest()
    }

    private fun envelopes(
        message: MessageEntity,
        self: PeerIdentity,
        plan: TransferPlan,
        chunks: ChunkReader,
    ): Sequence<MessageEnvelope> = sequence {
        val transferId = ByteString.copyFromUtf8(message.messageId)
        yield(header(message, self, plan, transferId))
        var index = 0
        while (index < plan.chunkCount) {
            val data = chunks.next(index) ?: break
            yield(
                MessageEnvelope.newBuilder()
                    .setMessageId(ByteString.copyFromUtf8("${message.messageId}-c$index"))
                    .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
                    .setSentAtUnixMs(System.currentTimeMillis())
                    .setCounter(1)
                    .setAttachmentChunk(
                        AttachmentChunk.newBuilder()
                            .setTransferId(transferId)
                            .setIndex(index)
                            .setData(ByteString.copyFrom(data)),
                    )
                    .build(),
            )
            index++
        }
    }

    private fun header(
        message: MessageEntity,
        self: PeerIdentity,
        plan: TransferPlan,
        transferId: ByteString,
    ): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8(message.messageId))
        .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
        .setSentAtUnixMs(message.createdAtUnixMs)
        .setCounter(1)
        .setAttachmentInfo(
            AttachmentInfo.newBuilder()
                .setTransferId(transferId)
                .setFileName(message.attachmentName.orEmpty())
                .setMimeType(message.attachmentMimeType.orEmpty())
                .setTotalSize(plan.size)
                .setChunkCount(plan.chunkCount)
                .setKind(message.contentType.toKind())
                .setSha256(ByteString.copyFrom(plan.sha256)),
        )
        .build()

    private fun MessageContentType.toKind(): AttachmentKind = when (this) {
        MessageContentType.IMAGE -> AttachmentKind.ATTACHMENT_KIND_IMAGE
        MessageContentType.VIDEO -> AttachmentKind.ATTACHMENT_KIND_VIDEO
        else -> AttachmentKind.ATTACHMENT_KIND_FILE
    }

    companion object {
        // Comfortably below the 1 MiB transport frame cap after proto + AEAD overhead.
        const val CHUNK_BYTES = 128 * 1024
        private const val SHA256_BYTES = 32

        /** Outbox `lastError` when the local file cannot be read (missing, corrupt or not encrypted). */
        const val READ_FAILED_MESSAGE = "پیوست قابل خواندن نیست"

        /** Plaintext bytes carried by chunk [index] of a [size]-byte transfer split into [chunkCount] chunks. */
        fun chunkSize(size: Long, chunkCount: Int, index: Int): Int =
            if (index < chunkCount - 1) CHUNK_BYTES else (size - index.toLong() * CHUNK_BYTES).toInt()

        private fun TransferPlan.chunkSize(index: Int): Int = chunkSize(size, chunkCount, index)

        private fun readFully(input: InputStream, buffer: ByteArray): Int {
            var offset = 0
            while (offset < buffer.size) {
                val n = input.read(buffer, offset, buffer.size - offset)
                if (n < 0) break
                offset += n
            }
            return offset
        }
    }
}
