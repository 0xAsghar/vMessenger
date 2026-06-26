package ir.vmessenger.core.common.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserHashEncoderTest {
    @Test
    fun encodeDecodeRoundTripForFullIdentityHash() {
        val hash = UserHashEncoder.identityHashFromPublicKey(ByteArray(32) { it.toByte() })
        val encoded = UserHashEncoder.encode(hash)
        val decoded = UserHashEncoder.decode(encoded)
        assertNotNull(decoded)
        assertTrue(hash.copyOf(16).contentEquals(decoded))
        assertEquals("ok", UserHashEncoder.decodeFailureReason(encoded))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val hash = ByteArray(16) { it.toByte() }
        val encoded = UserHashEncoder.encode(hash)
        assertTrue(encoded.startsWith("vm1-"))
        val decoded = UserHashEncoder.decode(encoded)
        assertNotNull(decoded)
        assertEquals(16, decoded!!.size)
        assertTrue(decoded.contentEquals(hash))
    }

    @Test
    fun decodeAcceptsUppercasePrefix() {
        val hash = ByteArray(16) { it.toByte() }
        val encoded = UserHashEncoder.encode(hash).uppercase()
        assertTrue(UserHashEncoder.isValid(encoded))
    }

    @Test
    fun invalidChecksumRejected() {
        val hash = ByteArray(16) { 0xAB.toByte() }
        val encoded = UserHashEncoder.encode(hash)
        val tampered = encoded.dropLast(1) + "Z"
        assertFalse(UserHashEncoder.isValid(tampered))
    }

    @Test
    fun identityHashFromPublicKeyIs32Bytes() {
        val key = ByteArray(32) { 1 }
        val hash = UserHashEncoder.identityHashFromPublicKey(key)
        assertEquals(32, hash.size)
    }
}
