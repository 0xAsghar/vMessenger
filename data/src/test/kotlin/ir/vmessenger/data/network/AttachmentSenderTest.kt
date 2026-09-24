package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.data.attachment.AttachmentTransferTracker
import ir.vmessenger.domain.model.AttachmentProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import ir.vmessenger.domain.model.MessageDirection as DomainDirection

class AttachmentSenderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val self = InboundFixtures.peer(0x01)
    private val peer = InboundFixtures.peer(0x0A)
    private val tracker = AttachmentTransferTracker()
    private val contentSource = FakeAttachmentContentSource()

    private val payload = Random(11).nextBytes(2 * AttachmentSender.CHUNK_BYTES + 777)

    @Test
    fun allChunksOnOneSession() = runTest {
        val batchSender = FakeAttachmentBatchSender()
        val sender = AttachmentSender(batchSender, contentSource, tracker)
        val (message, file) = queued(withDigest = true)

        val result = sender.sendTransfer("a", self, peer, message, file)

        assertTrue(result.result is AppResult.Success)
        assertNull(result.failedIndex)
        assertEquals(1, batchSender.batches.size)
        val batch = batchSender.batches.single()
        assertEquals(1 + 3, batch.size)
        val header = batch.first().attachmentInfo
        assertEquals(payload.size.toLong(), header.totalSize)
        assertEquals(3, header.chunkCount)
        assertArrayEquals(sha256(payload), header.sha256.toByteArray())
        val chunks = batch.drop(1).map { it.attachmentChunk }
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertArrayEquals(payload, chunks.flatMap { it.data.toByteArray().toList() }.toByteArray())
        assertTrue(chunks.all { it.transferId == header.transferId })
        // The stored digest was trusted: the file was opened once for streaming only.
        assertEquals(1, contentSource.opened)
    }

    @Test
    fun aTimedAttachmentsHeaderCarriesItsDeadline() = runTest {
        val batchSender = FakeAttachmentBatchSender()
        val sender = AttachmentSender(batchSender, contentSource, tracker)
        val (message, file) = queued(withDigest = true)

        sender.sendTransfer("a", self, peer, message.copy(expiresAtUnixMs = DEADLINE), file)

        val batch = batchSender.batches.single()
        assertEquals(DEADLINE, batch.first().expiresAtUnixMs)
        // An untimed file says nothing at all, which a 1.1.2 peer and this one both read as "never".
        sender.sendTransfer("a", self, peer, message, file)
        assertEquals(0L, batchSender.batches.last().first().expiresAtUnixMs)
    }

    @Test
    fun digestComputedWhenRowLacksIt() = runTest {
        val batchSender = FakeAttachmentBatchSender()
        val sender = AttachmentSender(batchSender, contentSource, tracker)
        val (message, file) = queued(withDigest = false)

        val result = sender.sendTransfer("a", self, peer, message, file)

        assertTrue(result.result is AppResult.Success)
        assertArrayEquals(sha256(payload), batchSender.batches.single().first().attachmentInfo.sha256.toByteArray())
        assertEquals(2, contentSource.opened)
    }

    @Test
    fun progressReported() = runTest {
        val batchSender = FakeAttachmentBatchSender()
        val sender = AttachmentSender(batchSender, contentSource, tracker)
        val (message, file) = queued(withDigest = true)
        val snapshots = mutableListOf<AttachmentProgress?>()
        batchSender.onSentHook = { snapshots += tracker.transfers.value[message.messageId] }

        sender.sendTransfer("a", self, peer, message, file)

        val total = payload.size.toLong()
        assertEquals(
            listOf(0L, AttachmentSender.CHUNK_BYTES.toLong(), 2L * AttachmentSender.CHUNK_BYTES, total),
            snapshots.map { it!!.bytesDone },
        )
        assertTrue(snapshots.all { it!!.totalBytes == total && it.direction == DomainDirection.OUTGOING })
        assertNull(tracker.transfers.value[message.messageId])
    }

    @Test
    fun failureReportsIndex() = runTest {
        val batchSender = FakeAttachmentBatchSender(failAt = 2)
        val sender = AttachmentSender(batchSender, contentSource, tracker)
        val (message, file) = queued(withDigest = true)

        val result = sender.sendTransfer("a", self, peer, message, file)

        assertTrue(result.result is AppResult.Error)
        assertEquals(2, result.failedIndex)
        assertEquals(2, result.sentCount)
        assertEquals(3, result.chunkCount)
        assertNull(tracker.transfers.value[message.messageId])
    }

    @Test
    fun missingFileFailsWithoutTouchingNetwork() = runTest {
        val batchSender = FakeAttachmentBatchSender()
        val sender = AttachmentSender(batchSender, contentSource, tracker)
        val (message, _) = queued(withDigest = true)

        val result = sender.sendTransfer("a", self, peer, message, File(folder.root, "gone.bin"))

        assertTrue(result.result is AppResult.Error)
        assertTrue(batchSender.batches.isEmpty())
    }

    private fun queued(withDigest: Boolean): Pair<MessageEntity, File> {
        val file = folder.newFile("photo.jpg").apply { writeBytes(payload) }
        val message = MessageEntity(
            messageId = "m1",
            conversationId = "c1",
            direction = MessageDirection.OUTGOING,
            contentType = MessageContentType.IMAGE,
            body = null,
            replyToMessageId = null,
            status = DeliveryStatus.QUEUED,
            createdAtUnixMs = 1_000L,
            sentAtUnixMs = null,
            deliveredAtUnixMs = null,
            readAtUnixMs = null,
            attachmentName = "photo.jpg",
            attachmentMimeType = "image/jpeg",
            attachmentSizeBytes = if (withDigest) payload.size.toLong() else null,
            attachmentPath = file.absolutePath,
            attachmentSha256 = if (withDigest) sha256(payload) else null,
            attachmentEncrypted = true,
        )
        return message to file
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val DEADLINE = 1_750_000_000_000L
    }
}
