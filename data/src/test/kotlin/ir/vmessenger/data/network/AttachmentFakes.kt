package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.attachment.AttachmentContentSource
import ir.vmessenger.data.attachment.AttachmentIncomingStore
import ir.vmessenger.data.attachment.IncomingStaging
import ir.vmessenger.network.messaging.PeerIdentity
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Records every batch handed to it. Each batch is drained like the real
 * service would (in order, `onSent` after each write); [failAt] aborts the
 * batch before writing that index, returning an error.
 */
class FakeAttachmentBatchSender(private val failAt: Int? = null) : AttachmentBatchSender {
    val batches = mutableListOf<List<MessageEnvelope>>()
    var onSentHook: (Int) -> Unit = {}

    override suspend fun sendBatch(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelopes: Sequence<MessageEnvelope>,
        onSent: (Int) -> Unit,
    ): AppResult<Unit> {
        val written = mutableListOf<MessageEnvelope>()
        batches += written
        for ((index, envelope) in envelopes.withIndex()) {
            if (index == failAt) return AppResult.Error(AppError.Network("simulated write failure"))
            written += envelope
            onSent(index)
            onSentHook(index)
        }
        return AppResult.Success(Unit)
    }
}

/** Plaintext files on disk stand in for the encrypted store. */
class FakeAttachmentContentSource : AttachmentContentSource {
    var opened = 0

    override suspend fun openDecrypted(path: String): InputStream {
        opened++
        return File(path).inputStream()
    }
}

/**
 * Stages chunks as plaintext under [root]/staging and "imports" by verifying
 * the digest and copying the file to [root]/in (the real store seals every
 * chunk and re-encrypts; the receiver's bookkeeping is what is under test).
 */
class FakeAttachmentIncomingStore(root: File) : AttachmentIncomingStore {
    val stagingDir = File(root, "staging").apply { mkdirs() }
    val inDir = File(root, "in").apply { mkdirs() }
    val imported = mutableListOf<File>()

    private class PlainStaging(val file: File, private val chunkBytes: Int) : IncomingStaging {
        val output = RandomAccessFile(file, "rw")

        override suspend fun writeChunk(index: Int, data: ByteArray) {
            output.seek(index.toLong() * chunkBytes)
            output.write(data)
        }

        override fun discard() {
            runCatching { output.close() }
            file.delete()
        }
    }

    override suspend fun newIncomingStaging(totalSize: Long, chunkCount: Int, chunkBytes: Int): IncomingStaging =
        PlainStaging(File(stagingDir, "${UUID.randomUUID()}.part"), chunkBytes)

    override suspend fun importStaged(staging: IncomingStaging, fileName: String, expectedSha256: ByteArray): File? {
        val plain = staging as PlainStaging
        plain.output.close()
        val digest = MessageDigest.getInstance("SHA-256").digest(plain.file.readBytes())
        val target = if (digest.contentEquals(expectedSha256)) {
            File(inDir, "${UUID.randomUUID()}-$fileName").also {
                plain.file.copyTo(it)
                imported += it
            }
        } else {
            null
        }
        plain.discard()
        return target
    }
}
