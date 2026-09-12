package ir.vmessenger.core.crypto.keystore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WrappedKeyBlobTest {
    private val iv = ByteArray(WrappedKeyBlob.IV_LENGTH) { it.toByte() }
    private val ciphertext = ByteArray(48) { (it + 100).toByte() }

    @Test
    fun roundTripsIvAndCiphertext() {
        val blob = WrappedKeyBlob.encode(iv, ciphertext)

        assertEquals(WrappedKeyBlob.VERSION, blob[0])
        assertEquals(1 + iv.size + ciphertext.size, blob.size)
        val parsed = WrappedKeyBlob.decode(blob)
        assertArrayEquals(iv, parsed.iv)
        assertArrayEquals(ciphertext, parsed.ciphertext)
    }

    @Test
    fun legacyBlobWithoutVersionByteIsRejected() {
        // 0.x dev builds stored a bare `iv ‖ ct`; the first byte is then iv[0], not 0x02.
        val legacy = ByteArray(WrappedKeyBlob.IV_LENGTH) { 0x11 } + ciphertext

        val error = assertThrows(IllegalArgumentException::class.java) { WrappedKeyBlob.decode(legacy) }
        assertEquals(true, error.message?.contains("unsupported wrapped-key format"))
    }

    @Test
    fun emptyOrTruncatedBlobIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { WrappedKeyBlob.decode(ByteArray(0)) }
        // Version byte plus a full IV but no room for the GCM tag.
        val truncated = byteArrayOf(WrappedKeyBlob.VERSION) + iv + ByteArray(4)
        assertThrows(IllegalArgumentException::class.java) { WrappedKeyBlob.decode(truncated) }
    }

    @Test
    fun encodeRejectsWrongIvLength() {
        assertThrows(IllegalArgumentException::class.java) { WrappedKeyBlob.encode(ByteArray(8), ciphertext) }
    }

    @Test
    fun aadIsAliasBound() {
        assertArrayEquals("vmessenger:db".toByteArray(), WrappedKeyBlob.aad("db"))
        assertEquals(false, WrappedKeyBlob.aad("db").contentEquals(WrappedKeyBlob.aad("attachments")))
    }
}
