package ir.vmessenger.data.attachment

import ir.vmessenger.core.crypto.CryptoEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.StreamCorruptedException
import java.nio.ByteBuffer

/**
 * One incoming transfer staged on disk while its chunks arrive (in any order).
 * Nothing about the transfer is ever written in the clear: every chunk is
 * sealed independently before it touches the file.
 */
interface IncomingStaging {
    /** Seals and stores the chunk at [index]; the same index may be written only once. */
    suspend fun writeChunk(index: Int, data: ByteArray)

    /** Closes and deletes the staging file and forgets the transfer key; safe to call more than once. */
    fun discard()
}

/**
 * Staging file layout: slot `i` (at `i * (chunkBytes + TAG)`) holds chunk `i`
 * sealed with XChaCha20-Poly1305 under a per-transfer key
 * `HKDF-SHA256(master, salt = stagingId, info = "vmessenger-attachment-staging-v1")`,
 * nonce = the chunk index, AAD = stagingId. A partial or abandoned transfer is
 * therefore as opaque on disk as a finished one, and a chunk cannot be moved to
 * another slot or another transfer without failing authentication.
 * [openPlaintext] reads the chunks back in order (each authenticated as it is
 * read) so the store can digest and re-encrypt the file without a plaintext copy.
 */
@Suppress("LongParameterList") // the transfer geometry (size, chunk count, chunk size) is fixed at creation
class SealedChunkStaging(
    private val cryptoEngine: CryptoEngine,
    masterKey: ByteArray,
    val file: File,
    private val totalSize: Long,
    private val chunkCount: Int,
    private val chunkBytes: Int,
    private val ioDispatcher: CoroutineDispatcher,
) : IncomingStaging {
    private val stagingId = cryptoEngine.randomBytes(ID_BYTES)
    private val key = cryptoEngine.hkdfSha256(masterKey, salt = stagingId, info = KDF_INFO, length = KEY_BYTES)
    private val slotBytes = chunkBytes.toLong() + TAG_BYTES

    @Volatile
    private var output: RandomAccessFile? = RandomAccessFile(file, "rw")

    init {
        require(chunkCount > 0 && totalSize > 0) { "empty transfer" }
        require(chunkBytes > 0) { "chunk size must be positive" }
    }

    override suspend fun writeChunk(index: Int, data: ByteArray) {
        require(index in 0 until chunkCount) { "chunk index out of range" }
        require(data.size == chunkSize(index)) { "chunk $index has ${data.size} bytes" }
        val sealed = cryptoEngine.xchacha20Poly1305Seal(key, nonceFor(index), data, stagingId)
        withContext(ioDispatcher) {
            val sink = checkNotNull(output) { "staging closed" }
            synchronized(sink) {
                sink.seek(index * slotBytes)
                sink.write(sealed)
            }
        }
    }

    /**
     * Plaintext of the staged chunks in index order. Blocking: call on an IO
     * dispatcher. Every chunk must have been written; a missing, moved or
     * tampered chunk surfaces as [StreamCorruptedException].
     */
    fun openPlaintext(): InputStream {
        closeOutput()
        return PlaintextStream(RandomAccessFile(file, "r"))
    }

    override fun discard() {
        closeOutput()
        file.delete()
        cryptoEngine.memzero(key)
    }

    private fun closeOutput() {
        output?.let { runCatching { it.close() } }
        output = null
    }

    private fun chunkSize(index: Int): Int =
        if (index < chunkCount - 1) chunkBytes else (totalSize - index.toLong() * chunkBytes).toInt()

    private fun nonceFor(index: Int): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).putInt(index).array()

    private inner class PlaintextStream(private val source: RandomAccessFile) : InputStream() {
        private var nextIndex = 0
        private var plain = ByteArray(0)
        private var plainPos = 0

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

        /** Opens the next chunk; false once every chunk has been read. */
        private fun fillNext(): Boolean {
            if (nextIndex >= chunkCount) return false
            val index = nextIndex++
            val sealed = ByteArray(chunkSize(index) + TAG_BYTES)
            source.seek(index * slotBytes)
            try {
                source.readFully(sealed)
            } catch (e: java.io.EOFException) {
                throw StreamCorruptedException("staged chunk $index missing").apply { initCause(e) }
            }
            val opened = cryptoEngine.xchacha20Poly1305Open(key, nonceFor(index), sealed, stagingId)
                ?: throw StreamCorruptedException("staged chunk $index rejected")
            plain.fill(0)
            plain = opened
            plainPos = 0
            return true
        }

        override fun close() {
            plain.fill(0)
            source.close()
        }
    }

    companion object {
        const val TAG_BYTES = 16
        private const val NONCE_BYTES = 24
        private const val KEY_BYTES = 32
        private const val ID_BYTES = 16
        private const val BYTE_MASK = 0xFF
        private val KDF_INFO = "vmessenger-attachment-staging-v1".toByteArray(Charsets.US_ASCII)
    }
}
