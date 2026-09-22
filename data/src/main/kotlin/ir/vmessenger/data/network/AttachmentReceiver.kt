package ir.vmessenger.data.network

import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.AttachmentInfo
import ir.vmessenger.core.proto.app.v1.AttachmentKind
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.attachment.AttachmentIncomingStore
import ir.vmessenger.data.attachment.AttachmentStore
import ir.vmessenger.data.attachment.AttachmentTransferTracker
import ir.vmessenger.data.attachment.IncomingStaging
import ir.vmessenger.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.domain.model.MessageDirection as DomainDirection

/** A fully received attachment ready for notification + delivery receipt. */
data class CompletedAttachment(
    val contactId: String,
    val messageId: String,
    val fileName: String,
    val conversationId: String,
    /** Group name for the notification title; null when the transfer was a 1:1 chat. */
    val groupName: String?,
)

/**
 * Reassembles incoming attachment transfers (header + chunks in any order) into
 * encrypted app-private files and materializes the chat message on completion.
 * Chunks go into the store's sealed staging ([IncomingStaging]: every chunk is
 * encrypted at rest on its own, so a partial transfer is never plaintext on
 * disk); a [BitSet] tracks which indices arrived (duplicates are ignored) and
 * the transfer completes when every bit is set, the byte count matches and the
 * header's SHA-256 verifies over the decrypted stream. A repeated header for
 * the same transfer resets partial state, so sender-side retries are safe.
 * Caps: [MAX_PER_CONTACT] pending transfers per contact, [MAX_GLOBAL] overall;
 * idle transfers are pruned by a timer started from [start].
 */
@Singleton
@Suppress("TooManyFunctions") // admission, chunk IO, verification, persistence and pruning of one transfer type
class AttachmentReceiver @Inject constructor(
    private val store: AttachmentIncomingStore,
    private val conversationDao: ConversationDao,
    private val conversationResolver: InboundConversationResolver,
    private val messageDao: MessageDao,
    private val tracker: AttachmentTransferTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Injectable clock so the prune timer is testable. */
    var clock: () -> Long = System::currentTimeMillis

    private class Pending(
        val contactId: String,
        val messageId: String,
        val info: AttachmentInfo,
        val staging: IncomingStaging,
        startedAt: Long,
        val target: InboundTarget,
    ) {
        val mutex = Mutex()
        val received = BitSet(info.chunkCount)
        var receivedBytes = 0L
        var lastActivityAt = startedAt
        var closed = false
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val registry = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler("Attachment"))
    private var pruneJob: Job? = null

    /** Sweeps whatever a killed process left staged, then prunes transfers that stall. */
    fun start() {
        if (pruneJob?.isActive == true) return
        pruneJob = scope.launch {
            sweepOrphanedStaging()
            while (isActive) {
                delay(PRUNE_INTERVAL_MS)
                pruneStale(clock())
            }
        }
    }

    /**
     * A process killed mid-receive leaves sealed chunk files on disk with nothing
     * left to finish them — the bookkeeping was in memory and the per-transfer key
     * died with the process — and no other path ever deletes them. Taken under the
     * lock that admits a transfer, and only with nothing in flight, so a live
     * staging can never be swept out from under its own chunks.
     */
    private suspend fun sweepOrphanedStaging() {
        registry.withLock {
            if (pending.isEmpty()) {
                runCatching { store.sweepOrphanedStaging() }
                    .onFailure { AppLogger.warn("Attachment", "staging sweep failed: ${it.message}") }
            }
        }
    }

    fun stop() {
        pruneJob?.cancel()
        pruneJob = null
    }

    /** Number of transfers currently being assembled (diagnostics/tests). */
    fun pendingCount(): Int = pending.size

    /** Returns true when the transfer was already delivered and only needs a fresh receipt. */
    @Suppress("ReturnCount") // duplicate, rejected header, cap reached: each is a distinct early exit
    suspend fun handleInfo(contactId: String, envelope: MessageEnvelope): Boolean {
        val info = envelope.attachmentInfo
        val messageId = envelope.messageId.toStringUtf8()
        val key = transferKey(info.transferId.toByteArray())
        if (messageId.isNotBlank() && messageDao.getById(messageId) != null) {
            AppLogger.info("Attachment", "duplicate transfer for delivered messageId=$messageId")
            return true
        }
        // Resolved here, not at send time: a transfer addressed to a group the
        // sender is not in must be refused before a single chunk is staged, and
        // the answer is kept so completion does not have to resolve it again.
        val target = conversationResolver.resolve(contactId, envelope, clock())
        if (messageId.isBlank() || target == null || !isAcceptable(info)) {
            AppLogger.warn(
                "Attachment",
                "rejected transfer size=${info.totalSize} chunks=${info.chunkCount} from contact=$contactId",
            )
            return false
        }
        val replaced = registry.withLock {
            val existing = pending[key]
            if (existing == null && !admits(contactId)) return false
            existing?.let { discard(it) }
            val staging = store.newIncomingStaging(info.totalSize, info.chunkCount, AttachmentSender.CHUNK_BYTES)
            val transfer = Pending(contactId, messageId, info, staging, clock(), target)
            pending[key] = transfer
            existing != null
        }
        tracker.update(messageId, contactId, 0L, info.totalSize, DomainDirection.INCOMING)
        AppLogger.info(
            "Attachment",
            (if (replaced) "header reset partial transfer" else "transfer started") +
                " messageId=$messageId size=${info.totalSize} chunks=${info.chunkCount}",
        )
        return false
    }

    /** Returns the completed attachment when this chunk finished the transfer. */
    suspend fun handleChunk(contactId: String, envelope: MessageEnvelope): CompletedAttachment? {
        val chunk = envelope.attachmentChunk
        val key = transferKey(chunk.transferId.toByteArray())
        // A chunk from anyone but the transfer's sender is dropped without touching its state.
        val transfer = pending[key]?.takeIf { it.contactId == contactId } ?: return null
        val outcome = transfer.mutex.withLock {
            if (transfer.closed) ChunkOutcome.CLOSED else write(transfer, chunk.index, chunk.data.toByteArray())
        }
        return when (outcome) {
            ChunkOutcome.COMPLETE -> complete(key, transfer)
            ChunkOutcome.INVALID -> {
                AppLogger.warn("Attachment", "invalid chunk ${chunk.index} for ${transfer.messageId}; transfer dropped")
                remove(key, transfer)
                null
            }
            ChunkOutcome.STORED, ChunkOutcome.DUPLICATE, ChunkOutcome.CLOSED -> null
        }
    }

    private enum class ChunkOutcome { STORED, DUPLICATE, COMPLETE, INVALID, CLOSED }

    private suspend fun write(transfer: Pending, index: Int, data: ByteArray): ChunkOutcome {
        val info = transfer.info
        val expected = if (index in 0 until info.chunkCount) {
            AttachmentSender.chunkSize(info.totalSize, info.chunkCount, index)
        } else {
            -1
        }
        return when {
            expected < 0 || data.size != expected -> ChunkOutcome.INVALID
            transfer.received.get(index) -> ChunkOutcome.DUPLICATE
            else -> store(transfer, index, data)
        }
    }

    private suspend fun store(transfer: Pending, index: Int, data: ByteArray): ChunkOutcome {
        val info = transfer.info
        transfer.staging.writeChunk(index, data)
        transfer.received.set(index)
        transfer.receivedBytes += data.size
        transfer.lastActivityAt = clock()
        tracker.update(
            transfer.messageId,
            transfer.contactId,
            transfer.receivedBytes,
            info.totalSize,
            DomainDirection.INCOMING,
        )
        val allBits = transfer.received.cardinality() == info.chunkCount
        return if (allBits && transfer.receivedBytes == info.totalSize) ChunkOutcome.COMPLETE else ChunkOutcome.STORED
    }

    /** Has the store verify the digest and encrypt the staged chunks into place, then persists the message. */
    private suspend fun complete(key: String, transfer: Pending): CompletedAttachment? {
        transfer.mutex.withLock { transfer.closed = true }
        val imported = runCatching {
            store.importStaged(transfer.staging, transfer.info.fileName, transfer.info.sha256.toByteArray())
        }
        val stored = imported.getOrNull()
        if (stored == null) {
            val reason = imported.exceptionOrNull()?.message ?: "sha256 mismatch"
            AppLogger.warn("Attachment", "transfer discarded messageId=${transfer.messageId}: $reason")
            remove(key, transfer)
            return null
        }
        pending.remove(key, transfer)
        tracker.remove(transfer.messageId)
        AppLogger.info(
            "Attachment",
            "transfer complete messageId=${transfer.messageId} bytes=${transfer.receivedBytes} sha256 ok",
        )
        return materialize(transfer, stored)
    }

    private suspend fun materialize(transfer: Pending, stored: File): CompletedAttachment {
        val info = transfer.info
        val now = clock()
        val fileName = info.fileName.ifBlank { stored.name }
        val conversationId = transfer.target.conversationId
        val albumId = info.albumId.toStringUtf8().ifBlank { null }
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
                attachmentName = fileName,
                attachmentMimeType = info.mimeType,
                attachmentSizeBytes = transfer.receivedBytes,
                attachmentPath = stored.absolutePath,
                attachmentSha256 = info.sha256.toByteArray(),
                attachmentEncrypted = true,
                senderIdentityHash = transfer.target.senderIdentityHash,
                caption = info.caption.take(MAX_CAPTION_LENGTH).ifBlank { null },
                // Duration and waveform arrived with the header, so a voice bubble
                // already had its shape while the audio was still streaming.
                attachmentDurationMs = info.durationMs.takeIf { it > 0 },
                attachmentWaveform = info.waveform.toByteArray().takeIf { it.size == WAVEFORM_BUCKETS },
                albumId = albumId,
                albumIndex = albumId?.let { info.albumIndex },
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
        return CompletedAttachment(
            contactId = transfer.contactId,
            messageId = transfer.messageId,
            fileName = fileName,
            conversationId = conversationId,
            groupName = transfer.target.groupName,
        )
    }

    /** Drops transfers that received nothing for [STALE_TRANSFER_MS]. */
    suspend fun pruneStale(now: Long) {
        val stale = pending.filterValues { now - it.lastActivityAt >= STALE_TRANSFER_MS }
        for ((key, transfer) in stale) {
            AppLogger.info("Attachment", "pruned stale transfer messageId=${transfer.messageId}")
            remove(key, transfer)
        }
    }

    private fun admits(contactId: String): Boolean {
        val perContact = pending.values.count { it.contactId == contactId }
        val ok = perContact < MAX_PER_CONTACT && pending.size < MAX_GLOBAL
        if (!ok) {
            AppLogger.warn("Attachment", "transfer cap reached contact=$contactId pending=$perContact/${pending.size}")
        }
        return ok
    }

    private suspend fun remove(key: String, transfer: Pending) {
        if (pending.remove(key, transfer)) discard(transfer)
    }

    private suspend fun discard(transfer: Pending) {
        transfer.mutex.withLock { transfer.closed = true }
        withContext(ioDispatcher) { runCatching { transfer.staging.discard() } }
        tracker.remove(transfer.messageId)
    }

    private fun isAcceptable(info: AttachmentInfo): Boolean {
        val chunkBytes = AttachmentSender.CHUNK_BYTES
        val expectedChunks = ((info.totalSize + chunkBytes - 1) / chunkBytes).toInt()
        return info.totalSize in 1..AttachmentStore.MAX_ATTACHMENT_BYTES &&
            info.chunkCount == expectedChunks &&
            info.sha256.size() == SHA256_BYTES
    }

    private fun transferKey(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }

    private fun AttachmentKind.toContentType() = when (this) {
        AttachmentKind.ATTACHMENT_KIND_IMAGE -> MessageContentType.IMAGE
        AttachmentKind.ATTACHMENT_KIND_VIDEO -> MessageContentType.VIDEO
        AttachmentKind.ATTACHMENT_KIND_AUDIO -> MessageContentType.AUDIO
        else -> MessageContentType.FILE
    }

    companion object {
        const val MAX_PER_CONTACT = 2
        const val MAX_GLOBAL = 8
        const val PRUNE_INTERVAL_MS = 60_000L
        const val STALE_TRANSFER_MS = 10 * 60_000L
        private const val SHA256_BYTES = 32
        private const val MAX_CAPTION_LENGTH = 1024

        /** A waveform is exactly this many buckets; anything else is not one and is dropped. */
        private const val WAVEFORM_BUCKETS = 64
    }
}
