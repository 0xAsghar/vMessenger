package ir.vmessenger.data.attachment

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.io.StreamCorruptedException
import kotlin.random.Random

class SealedChunkStagingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var engine: LazysodiumCryptoEngine
    private val master = ByteArray(32) { it.toByte() }
    private val payload = Random(11).nextBytes(2 * CHUNK + 777)
    private val chunkCount = 3

    @Before
    fun setUp() {
        engine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    }

    @Test
    fun outOfOrderChunksReadBackInOrder() = runTest {
        val staging = staging()

        staging.writeChunk(2, slice(2))
        staging.writeChunk(0, slice(0))
        staging.writeChunk(1, slice(1))

        assertArrayEquals(payload, staging.openPlaintext().use { it.readBytes() })
        staging.discard()
        assertFalse(staging.file.exists())
    }

    @Test
    fun nothingOnDiskInTheClear() = runTest {
        val staging = staging()
        staging.writeChunk(0, slice(0))
        staging.writeChunk(1, slice(1))
        staging.writeChunk(2, slice(2))

        val onDisk = staging.file.readBytes()
        val slot = CHUNK + SealedChunkStaging.TAG_BYTES
        assertEquals((chunkCount - 1) * slot + 777 + SealedChunkStaging.TAG_BYTES, onDisk.size)
        for (index in 0 until chunkCount) {
            val window = slice(index).copyOf(48)
            assertFalse("chunk $index appears in the clear", onDisk.contains(window))
        }
        staging.discard()
    }

    @Test
    fun tamperedChunkRejected() = runTest {
        val staging = staging()
        for (index in 0 until chunkCount) staging.writeChunk(index, slice(index))
        RandomAccessFile(staging.file, "rw").use { raf ->
            raf.seek((CHUNK + SealedChunkStaging.TAG_BYTES) + 10L)
            val b = raf.read()
            raf.seek((CHUNK + SealedChunkStaging.TAG_BYTES) + 10L)
            raf.write(b xor 0x01)
        }

        assertThrows(StreamCorruptedException::class.java) {
            staging.openPlaintext().use { it.readBytes() }
        }
        staging.discard()
    }

    @Test
    fun missingChunkRejected() = runTest {
        val staging = staging()
        staging.writeChunk(0, slice(0))
        staging.writeChunk(2, slice(2))

        assertThrows(StreamCorruptedException::class.java) {
            staging.openPlaintext().use { it.readBytes() }
        }
        staging.discard()
    }

    @Test
    fun chunkCannotMoveBetweenSlots() = runTest {
        val staging = staging()
        for (index in 0 until chunkCount) staging.writeChunk(index, slice(index))
        // Swap the sealed bytes of slots 0 and 1: both authenticate on their own, neither in the other's place.
        val slot = CHUNK + SealedChunkStaging.TAG_BYTES
        val bytes = staging.file.readBytes()
        val swapped = bytes.copyOfRange(slot, 2 * slot) +
            bytes.copyOfRange(0, slot) +
            bytes.copyOfRange(2 * slot, bytes.size)
        staging.file.writeBytes(swapped)

        assertThrows(StreamCorruptedException::class.java) {
            staging.openPlaintext().use { it.readBytes() }
        }
        staging.discard()
    }

    @Test
    fun wrongSizedChunkRefused() = runTest {
        val staging = staging()
        val tooShort = runCatching { staging.writeChunk(0, ByteArray(10)) }.exceptionOrNull()
        val outOfRange = runCatching { staging.writeChunk(chunkCount, slice(0)) }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $tooShort", tooShort is IllegalArgumentException)
        assertTrue("expected IllegalArgumentException, got $outOfRange", outOfRange is IllegalArgumentException)
        staging.discard()
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    private fun staging(file: File = File(folder.root, "t.part")) = SealedChunkStaging(
        cryptoEngine = engine,
        masterKey = master,
        file = file,
        totalSize = payload.size.toLong(),
        chunkCount = chunkCount,
        chunkBytes = CHUNK,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun slice(index: Int): ByteArray {
        val from = index * CHUNK
        return payload.copyOfRange(from, minOf(from + CHUNK, payload.size))
    }

    private fun ByteArray.contains(window: ByteArray): Boolean {
        outer@ for (start in 0..size - window.size) {
            for (i in window.indices) if (this[start + i] != window[i]) continue@outer
            return true
        }
        return false
    }

    private companion object {
        const val CHUNK = 4 * 1024
    }
}
