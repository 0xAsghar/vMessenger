package ir.vmessenger.core.common.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

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
        assertTrue(encoded.startsWith("vm-"))
        val decoded = UserHashEncoder.decode(encoded)
        assertNotNull(decoded)
        assertEquals(16, decoded!!.size)
        assertTrue(decoded.contentEquals(hash))
    }

    @Test
    fun encodedLengthIs29() {
        val encoded = UserHashEncoder.encode(ByteArray(32) { (it * 7).toByte() })
        val groups = encoded.removePrefix("vm-").split("-")
        assertEquals(listOf(5, 5, 5, 5, 5, 4), groups.map { it.length })
        assertEquals(29, groups.sumOf { it.length })
        assertEquals("vm-".length + 29 + 5, encoded.length)
    }

    @Test
    fun decodeAcceptsUppercasePrefixAndUnicodeDashes() {
        val hash = ByteArray(16) { it.toByte() }
        val encoded = UserHashEncoder.encode(hash)
        assertTrue(UserHashEncoder.isValid(encoded.uppercase()))
        assertTrue(UserHashEncoder.isValid(encoded.replace('-', '–')))
        assertTrue(UserHashEncoder.isValid(" $encoded​ "))
    }

    @Test
    fun theOlderVm2FormStillDecodesToTheSameIdentity() {
        // Every ID written before 2.0.0-beta.1, and every one a 1.1.2 peer still shares.
        val hash = ByteArray(16) { it.toByte() }
        val vm = UserHashEncoder.encode(hash)
        val vm2 = "vm2-" + vm.removePrefix("vm-")
        val decodedVm2 = UserHashEncoder.decode(vm2)
        assertNotNull(decodedVm2)
        assertTrue(decodedVm2!!.contentEquals(hash))
        assertEquals("ok", UserHashEncoder.decodeFailureReason(vm2))
        // Both prefixes of the same identity decode to the same bytes — the body is prefix-independent.
        assertTrue(UserHashEncoder.decode(vm)!!.contentEquals(decodedVm2))
    }

    @Test
    fun theOlderVm2FormToleratesUppercaseAndUnicodeDashes() {
        val hash = ByteArray(16) { (it * 3 + 1).toByte() }
        val vm2 = "vm2-" + UserHashEncoder.encode(hash).removePrefix("vm-")
        assertTrue(UserHashEncoder.isValid(vm2.uppercase()))
        assertTrue(UserHashEncoder.isValid(vm2.replace('-', '–')))
    }

    @Test
    fun invalidChecksumRejected() {
        val hash = ByteArray(16) { 0xAB.toByte() }
        val encoded = UserHashEncoder.encode(hash)
        // 'R' = 0b10110 and '0' = 0b00000 differ in data bits, not only in the trailing pad bit.
        val tampered = encoded.dropLast(1) + if (encoded.last() == 'R') "0" else "R"
        assertFalse(UserHashEncoder.isValid(tampered))
        assertEquals("checksum_mismatch", UserHashEncoder.decodeFailureReason(tampered))
    }

    @Test
    fun nonCanonicalPadBitRejected() {
        val encoded = UserHashEncoder.encode(ByteArray(16) { 0x5A })
        val last = encoded.last()
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        // Flip only the lowest bit of the last symbol: same 144 payload bits, different string.
        val flippedPad = alphabet[alphabet.indexOf(last) xor 1]
        assertNull(UserHashEncoder.decode(encoded.dropLast(1) + flippedPad))
    }

    @Test
    fun checksumCoversFirstByte() {
        val encoded = UserHashEncoder.encode(ByteArray(16) { (it + 1).toByte() })
        // The first base32 symbol carries the top five bits of prefix byte 0; v1's XOR over the
        // last two bytes would not notice this flip, the v2 SHA-256 checksum must.
        val start = "vm-".length
        val first = encoded[start]
        val replacement = if (first == '0') '1' else '0'
        val flipped = encoded.substring(0, start) + replacement + encoded.substring(start + 1)
        assertNull(UserHashEncoder.decode(flipped))
        assertEquals("checksum_mismatch", UserHashEncoder.decodeFailureReason(flipped))
    }

    @Test
    fun checksumIsSha256OfTagAndPrefix() {
        val prefix = ByteArray(16) { (0x40 + it).toByte() }
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("vmessenger-userhash-v2".toByteArray() + prefix)
            .copyOf(2)
        val payload = crockfordDecode(UserHashEncoder.encode(prefix).removePrefix("vm-").replace("-", ""))
        assertEquals(18, payload.size)
        assertTrue(prefix.contentEquals(payload.copyOf(16)))
        assertTrue(expected.contentEquals(payload.copyOfRange(16, 18)))
    }

    /** Independent Crockford base32 decoder so the test does not trust the encoder's own decode path. */
    private fun crockfordDecode(value: String): ByteArray {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var buffer = 0L
        var bits = 0
        val out = ArrayList<Byte>()
        for (ch in value) {
            buffer = (buffer shl 5) or alphabet.indexOf(ch).also { assertTrue(it >= 0) }.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    @Test
    fun vm1Rejected() {
        val encoded = UserHashEncoder.encode(ByteArray(16) { it.toByte() })
        val legacy = "vm1-" + encoded.removePrefix("vm-")
        assertNull(UserHashEncoder.decode(legacy))
        assertEquals("missing_prefix", UserHashEncoder.decodeFailureReason(legacy))
        assertNull(UserHashEncoder.decode(encoded.removePrefix("vm-")))
        assertEquals("missing_prefix", UserHashEncoder.decodeFailureReason(encoded.removePrefix("vm-")))
    }

    @Test
    fun wrongLengthRejected() {
        val encoded = UserHashEncoder.encode(ByteArray(16) { it.toByte() })
        assertNull(UserHashEncoder.decode(encoded.dropLast(1)))
        assertEquals("too_short(len=28)", UserHashEncoder.decodeFailureReason(encoded.dropLast(1)))
        assertNull(UserHashEncoder.decode(encoded + "0"))
        assertEquals("too_long(len=30)", UserHashEncoder.decodeFailureReason(encoded + "0"))
        assertEquals("empty", UserHashEncoder.decodeFailureReason("  "))
        assertEquals("invalid_character", UserHashEncoder.decodeFailureReason(encoded.dropLast(1) + "U"))
    }

    @Test
    fun identityHashFromPublicKeyIs32Bytes() {
        val key = ByteArray(32) { 1 }
        val hash = UserHashEncoder.identityHashFromPublicKey(key)
        assertEquals(32, hash.size)
    }
}
