package ir.vmessenger.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519VerifierTest {

    @Test
    fun `accepts RFC 8032 test 2`() {
        assertTrue(Ed25519Verifier.verify(RFC_SIG, RFC_MSG, RFC_PUB))
    }

    @Test
    fun `accepts what a fresh identity signs and nothing else`() {
        val id = TestIdentity.generate()
        val message = "vmessenger".toByteArray()
        val signature = id.sign(message)

        assertTrue(Ed25519Verifier.verify(signature, message, id.pub))
        assertFalse(Ed25519Verifier.verify(signature, "vmessengeR".toByteArray(), id.pub))
        assertFalse(Ed25519Verifier.verify(signature, message, TestIdentity.generate().pub))
        val flipped = signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(Ed25519Verifier.verify(flipped, message, id.pub))
    }

    @Test
    fun `wrong sizes are refused, not thrown`() {
        assertFalse(Ed25519Verifier.verify(RFC_SIG.copyOf(63), RFC_MSG, RFC_PUB))
        assertFalse(Ed25519Verifier.verify(RFC_SIG, RFC_MSG, RFC_PUB.copyOf(31)))
        assertFalse(Ed25519Verifier.verify(ByteArray(0), RFC_MSG, ByteArray(0)))
    }

    @Test
    fun `S at or above the group order is refused, as libsodium does`() {
        // S + L verifies under a lax RFC 8032 check: it is the same scalar mod L.
        val s = java.math.BigInteger(1, RFC_SIG.copyOfRange(32, 64).reversedArray()) + GROUP_ORDER
        val sBytes = s.toByteArray().reversedArray().copyOf(32)
        val malleated = RFC_SIG.copyOfRange(0, 32) + sBytes

        assertFalse(Ed25519Verifier.isCanonicalScalar(sBytes))
        assertFalse(Ed25519Verifier.verify(malleated, RFC_MSG, RFC_PUB))
    }

    @Test
    fun `small-order public keys and R values are refused`() {
        SMALL_ORDER_HEX.map(::hex).forEach { point ->
            assertTrue(Ed25519Verifier.hasSmallOrder(point))
            val signBitSet = point.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }
            assertTrue(Ed25519Verifier.hasSmallOrder(signBitSet))
            assertFalse(Ed25519Verifier.verify(RFC_SIG, RFC_MSG, point))
            assertFalse(Ed25519Verifier.verify(point + RFC_SIG.copyOfRange(32, 64), RFC_MSG, RFC_PUB))
        }
        assertFalse(Ed25519Verifier.hasSmallOrder(RFC_PUB))
    }

    @Test
    fun `a public key whose y is not below p is refused`() {
        // y = p + 2: not in the blocklist, but not a canonical encoding either.
        val nonCanonical = hex("efffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")

        assertFalse(Ed25519Verifier.isCanonicalPoint(nonCanonical))
        assertTrue(Ed25519Verifier.isCanonicalPoint(RFC_PUB))
        assertFalse(Ed25519Verifier.verify(RFC_SIG, RFC_MSG, nonCanonical))
    }

    private companion object {
        // RFC 8032 §7.1 TEST 2, cross-checked against OpenSSL.
        val RFC_PUB = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val RFC_MSG = hex("72")
        val RFC_SIG = hex(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        )

        val GROUP_ORDER: java.math.BigInteger =
            java.math.BigInteger.TWO.pow(252) + java.math.BigInteger("27742317777372353535851937790883648493")

        val SMALL_ORDER_HEX = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        )

        fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i ->
            text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
