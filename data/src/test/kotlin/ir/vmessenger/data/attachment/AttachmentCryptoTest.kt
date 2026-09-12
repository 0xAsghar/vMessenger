package ir.vmessenger.data.attachment

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.crypto.stream.LazysodiumSecretStream
import ir.vmessenger.core.crypto.stream.SecretStreamCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.StreamCorruptedException
import kotlin.random.Random

class AttachmentCryptoTest {
    private lateinit var crypto: AttachmentCrypto
    private lateinit var engine: LazysodiumCryptoEngine
    private val master = ByteArray(32) { it.toByte() }

    @Before
    fun setUp() {
        val sodium = LazySodiumJava(SodiumJava())
        engine = LazysodiumCryptoEngine(sodium)
        crypto = AttachmentCrypto(engine, LazysodiumSecretStream(sodium))
    }

    @Test
    fun streamRoundTrip() {
        // Three full chunks plus a partial one, so buffering and the FINAL tag are both exercised.
        val plaintext = Random(7).nextBytes(3 * AttachmentCrypto.CHUNK_BYTES + 12_345)

        val container = encrypt(plaintext)
        val decrypted = crypto.decrypt(master, ByteArrayInputStream(container)).use { it.readBytes() }

        assertArrayEquals(plaintext, decrypted)
        assertArrayEquals(AttachmentCrypto.MAGIC, container.copyOfRange(0, 4))
        val prefix = 4 + AttachmentCrypto.FILE_ID_BYTES + SecretStreamCipher.HEADER_BYTES
        assertEquals(plaintext.size + prefix + 4 * SecretStreamCipher.TAG_BYTES, container.size)
    }

    @Test
    fun emptyAndChunkAlignedInputsRoundTrip() {
        for (size in listOf(0, 1, AttachmentCrypto.CHUNK_BYTES, 2 * AttachmentCrypto.CHUNK_BYTES)) {
            val plaintext = Random(size).nextBytes(size)
            val decrypted = crypto.decrypt(master, ByteArrayInputStream(encrypt(plaintext))).use { it.readBytes() }
            assertArrayEquals("size=$size", plaintext, decrypted)
        }
    }

    @Test
    fun tamperDetected() {
        val plaintext = Random(1).nextBytes(AttachmentCrypto.CHUNK_BYTES + 100)
        val container = encrypt(plaintext)
        val flipped = container.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 0x01).toByte() }

        assertThrows(StreamCorruptedException::class.java) {
            crypto.decrypt(master, ByteArrayInputStream(flipped)).use { it.readBytes() }
        }
    }

    @Test
    fun truncatedStreamDetected() {
        val plaintext = Random(2).nextBytes(2 * AttachmentCrypto.CHUNK_BYTES)
        val container = encrypt(plaintext)
        // Cut exactly at a chunk boundary: the remaining chunk authenticates, but FINAL never arrives.
        val cut = container.copyOf(container.size - (AttachmentCrypto.CHUNK_BYTES + SecretStreamCipher.TAG_BYTES))

        assertThrows(EOFException::class.java) {
            crypto.decrypt(master, ByteArrayInputStream(cut)).use { it.readBytes() }
        }
    }

    @Test
    fun wrongKeyFails() {
        val container = encrypt(Random(3).nextBytes(500))
        val otherKey = ByteArray(32) { (it + 1).toByte() }

        assertThrows(StreamCorruptedException::class.java) {
            crypto.decrypt(otherKey, ByteArrayInputStream(container)).use { it.readBytes() }
        }
    }

    @Test
    fun legacyPlaintextRefused() {
        val legacy = "just a plain file that predates encryption".toByteArray()

        val error = assertThrows(StreamCorruptedException::class.java) {
            crypto.decrypt(master, ByteArrayInputStream(legacy))
        }
        assertTrue(error.message!!.contains("not an encrypted attachment"))
    }

    @Test
    fun everyFileGetsItsOwnIdAndKeyStream() {
        val plaintext = Random(4).nextBytes(64)
        val a = encrypt(plaintext)
        val b = encrypt(plaintext)
        val fileIdA = a.copyOfRange(4, 4 + AttachmentCrypto.FILE_ID_BYTES)
        val fileIdB = b.copyOfRange(4, 4 + AttachmentCrypto.FILE_ID_BYTES)
        assertTrue(!fileIdA.contentEquals(fileIdB))
        // Swapping the body under another file's id/header must not open.
        val swapped = a.copyOfRange(0, 4 + AttachmentCrypto.FILE_ID_BYTES) +
            b.copyOfRange(4 + AttachmentCrypto.FILE_ID_BYTES, b.size)
        assertThrows(StreamCorruptedException::class.java) {
            crypto.decrypt(master, ByteArrayInputStream(swapped)).use { it.readBytes() }
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        crypto.encrypt(master, out).use { it.write(plaintext) }
        return out.toByteArray()
    }
}
