package ir.vmessenger.data.attachment

import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.stream.SecretStreamCipher
import ir.vmessenger.core.crypto.stream.SecretStreamPuller
import ir.vmessenger.core.crypto.stream.SecretStreamPusher
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.StreamCorruptedException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attachment-at-rest container: `"VMA1" ‖ fileId(16) ‖ secretstream header(24) ‖ chunks`, where the
 * chunks are libsodium `secretstream_xchacha20poly1305` frames of at most [CHUNK_BYTES] plaintext
 * each (the last one tagged FINAL, so truncation is detected). The per-file key is
 * `HKDF-SHA256(master, salt = fileId, info = "vmessenger-attachment-v1")`; a key/header swapped in
 * from another file therefore fails to open. Plaintext never touches disk through these streams.
 */
@Singleton
class AttachmentCrypto @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val secretStream: SecretStreamCipher,
) {
    /** Wraps [output] so every byte written is encrypted; `close()` seals the final chunk. */
    fun encrypt(masterKey: ByteArray, output: OutputStream): OutputStream {
        val fileId = cryptoEngine.randomBytes(FILE_ID_BYTES)
        val fileKey = fileKey(masterKey, fileId)
        val pusher = try {
            secretStream.push(fileKey)
        } finally {
            cryptoEngine.memzero(fileKey)
        }
        output.write(MAGIC)
        output.write(fileId)
        output.write(pusher.header)
        return EncryptingStream(pusher, output)
    }

    /**
     * Wraps [input] so reads return plaintext. Throws [StreamCorruptedException] when [input] is
     * not a `VMA1` container (e.g. a legacy plaintext file) or, during reads, on any tampering.
     */
    fun decrypt(masterKey: ByteArray, input: InputStream): InputStream {
        val magic = ByteArray(MAGIC.size)
        val fileId = ByteArray(FILE_ID_BYTES)
        val header = ByteArray(SecretStreamCipher.HEADER_BYTES)
        val prefixOk = input.readFully(magic) == magic.size &&
            magic.contentEquals(MAGIC) &&
            input.readFully(fileId) == fileId.size &&
            input.readFully(header) == header.size
        if (!prefixOk) throw StreamCorruptedException("not an encrypted attachment")
        val fileKey = fileKey(masterKey, fileId)
        val puller = try {
            secretStream.pull(fileKey, header)
        } finally {
            cryptoEngine.memzero(fileKey)
        }
        return DecryptingStream(puller ?: throw StreamCorruptedException("attachment header rejected"), input)
    }

    private fun fileKey(masterKey: ByteArray, fileId: ByteArray): ByteArray {
        require(masterKey.size == SecretStreamCipher.KEY_BYTES) { "attachment master key must be 32 bytes" }
        return cryptoEngine.hkdfSha256(masterKey, salt = fileId, info = KDF_INFO, length = SecretStreamCipher.KEY_BYTES)
    }

    private class EncryptingStream(
        private val pusher: SecretStreamPusher,
        private val output: OutputStream,
    ) : OutputStream() {
        private val buffer = ByteArray(CHUNK_BYTES)
        private var filled = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            var offset = off
            var remaining = len
            while (remaining > 0) {
                // A full buffer is only flushed once more data arrives, so the last chunk can be tagged FINAL.
                if (filled == CHUNK_BYTES) flushChunk(isFinal = false)
                val n = minOf(remaining, CHUNK_BYTES - filled)
                System.arraycopy(b, offset, buffer, filled, n)
                filled += n
                offset += n
                remaining -= n
            }
        }

        override fun flush() = output.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushChunk(isFinal = true)
                output.flush()
            } finally {
                buffer.fill(0)
                pusher.close()
                output.close()
            }
        }

        private fun flushChunk(isFinal: Boolean) {
            output.write(pusher.push(buffer, filled, isFinal))
            filled = 0
        }
    }

    private class DecryptingStream(
        private val puller: SecretStreamPuller,
        private val input: InputStream,
    ) : InputStream() {
        private val cipherBuffer = ByteArray(CHUNK_BYTES + SecretStreamCipher.TAG_BYTES)
        private var plain = ByteArray(0)
        private var plainPos = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and BYTE_MASK else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val available = len > 0 && (plainPos < plain.size || fillNext())
            if (!available) return if (len == 0) 0 else -1
            val n = minOf(len, plain.size - plainPos)
            System.arraycopy(plain, plainPos, b, off, n)
            plainPos += n
            return n
        }

        /** Pulls the next chunk; false at a clean end of stream. */
        private fun fillNext(): Boolean {
            if (finished) return false
            val n = input.readFully(cipherBuffer)
            if (n == 0) throw EOFException("attachment truncated: missing final chunk")
            val chunk = puller.pull(cipherBuffer, n) ?: throw StreamCorruptedException("attachment chunk rejected")
            plain.fill(0)
            plain = chunk.data
            plainPos = 0
            finished = chunk.isFinal
            return plain.isNotEmpty() || fillNext()
        }

        override fun close() {
            plain.fill(0)
            cipherBuffer.fill(0)
            puller.close()
            input.close()
        }
    }

    companion object {
        val MAGIC: ByteArray = "VMA1".toByteArray(Charsets.US_ASCII)
        const val FILE_ID_BYTES = 16
        const val CHUNK_BYTES = 64 * 1024
        private const val BYTE_MASK = 0xFF
        private val KDF_INFO = "vmessenger-attachment-v1".toByteArray(Charsets.US_ASCII)

        /** Reads until [buffer] is full or EOF; returns the number of bytes read. */
        private fun InputStream.readFully(buffer: ByteArray): Int {
            var offset = 0
            while (offset < buffer.size) {
                val n = read(buffer, offset, buffer.size - offset)
                if (n < 0) break
                offset += n
            }
            return offset
        }
    }
}
