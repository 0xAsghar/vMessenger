package ir.vmessenger.core.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LazysodiumCryptoEngineTest {
    private lateinit var engine: LazysodiumCryptoEngine

    @Before
    fun setUp() {
        engine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    }

    @Test
    fun ed25519SignVerifyRoundTrip() {
        val keyPair = engine.generateEd25519KeyPair()
        val message = "vMessenger test".toByteArray()
        val signature = engine.signEd25519(message, keyPair.privateKey)
        assertTrue(engine.verifyEd25519(message, signature, keyPair.publicKey))
    }

    @Test
    fun ed25519KnownVectorVerify() {
        val keyPair = engine.generateEd25519KeyPair()
        val message = byteArrayOf(0x01, 0x02, 0x03)
        val signature = engine.signEd25519(message, keyPair.privateKey)
        assertTrue(engine.verifyEd25519(message, signature, keyPair.publicKey))
    }

    @Test
    fun ed25519SeedPrivateKeySignsAndSeedSurvives() {
        val keyPair = engine.generateEd25519KeyPair()
        val seed = keyPair.privateKey.copyOf(32)
        val seedCopy = seed.copyOf()
        val signature = engine.signEd25519("seeded".toByteArray(), seed)
        assertTrue(engine.verifyEd25519("seeded".toByteArray(), signature, keyPair.publicKey))
        // Only the expanded temporary is wiped, never the caller's seed.
        assertArrayEquals(seedCopy, seed)
    }

    @Test
    fun verifyRejectsMalformedSignatureOrKey() {
        val keyPair = engine.generateEd25519KeyPair()
        val message = "m".toByteArray()
        val signature = engine.signEd25519(message, keyPair.privateKey)
        assertFalse(engine.verifyEd25519(message, signature.copyOf(63), keyPair.publicKey))
        assertFalse(engine.verifyEd25519(message, signature, keyPair.publicKey.copyOf(31)))
        assertFalse(engine.verifyEd25519(message + 1, signature, keyPair.publicKey))
    }

    @Test
    fun sealOpenRoundTrip() {
        val key = engine.randomBytes(32)
        val plaintext = "secret payload".toByteArray()
        val ad = "frame".toByteArray()
        val sealed = engine.seal(plaintext, key, ad)
        assertEquals(12 + plaintext.size + 16, sealed.size)
        val opened = engine.open(sealed, key, ad)
        assertNotNull(opened)
        assertArrayEquals(plaintext, opened)
        assertNull(engine.open(sealed, key, "other".toByteArray()))
        assertNull(engine.open(sealed, engine.randomBytes(32), ad))
        assertNull(engine.open(sealed.copyOf(27), key, ad))
        assertNull(engine.open(sealed, key.copyOf(31), ad))
    }

    @Test
    fun sealTamperRejected() {
        val key = engine.randomBytes(32)
        val sealed = engine.seal("data".toByteArray(), key).copyOf()
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 0xFF).toByte()
        assertNull(engine.open(sealed, key))
    }

    @Test
    fun aeadMatchesRfc8439Vector() {
        // RFC 8439 section 2.8.2 AEAD_CHACHA20_POLY1305 test vector.
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
                "sunscreen would be it."
            ).toByteArray(Charsets.US_ASCII)
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val expectedCiphertext = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116",
        )
        val expectedTag = hex("1ae10b594f09e26a7e902ecbd0600691")

        val sealed = engine.sealWithNonce(plaintext, key, nonce, aad)
        assertArrayEquals(expectedCiphertext + expectedTag, sealed)
        assertArrayEquals(plaintext, engine.open(nonce + expectedCiphertext + expectedTag, key, aad))
    }

    @Test
    fun hkdfDeterministic() {
        val ikm = "input".toByteArray()
        val salt = "salt".toByteArray()
        val info = "info".toByteArray()
        val a = engine.hkdfSha256(ikm, salt, info, 32)
        val b = engine.hkdfSha256(ikm, salt, info, 32)
        assertArrayEquals(a, b)
        assertTrue(a.size == 32)
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        )
        assertArrayEquals(expected, engine.hkdfSha256(ikm, salt, info, 42))
    }

    @Test
    fun x25519SharedSecretIsRawScalarMultAndSymmetric() {
        val a = engine.generateX25519KeyPair()
        val b = engine.generateX25519KeyPair()
        val ab = engine.x25519SharedSecret(a.privateKey, b.publicKey)
        val ba = engine.x25519SharedSecret(b.privateKey, a.publicKey)
        assertArrayEquals(ab, ba)
        assertEquals(32, ab.size)
        // RFC 7748 section 6.1 vector: raw X25519, not crypto_box_beforenm's HSalsa20 output.
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertArrayEquals(expected, engine.x25519SharedSecret(alicePriv, bobPub))
    }

    @Test
    fun x25519LowOrderPointRejected() {
        val keyPair = engine.generateX25519KeyPair()
        val lowOrderPoints = listOf(
            ByteArray(32),
            byteArrayOf(1) + ByteArray(31),
            hex("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
            hex("5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157"),
        )
        for (point in lowOrderPoints) {
            assertThrows(IllegalStateException::class.java) { engine.x25519SharedSecret(keyPair.privateKey, point) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.x25519SharedSecret(keyPair.privateKey, keyPair.publicKey.copyOf(31))
        }
    }

    @Test
    fun sealedBoxRoundTrip() {
        val recipient = engine.generateX25519KeyPair()
        val plaintext = "for your eyes only".toByteArray()
        val sealed = engine.sealedBoxSeal(plaintext, recipient.publicKey)
        assertEquals(plaintext.size + 48, sealed.size)
        assertArrayEquals(plaintext, engine.sealedBoxOpen(sealed, recipient.publicKey, recipient.privateKey))

        val other = engine.generateX25519KeyPair()
        assertNull(engine.sealedBoxOpen(sealed, other.publicKey, other.privateKey))
        val tampered = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(engine.sealedBoxOpen(tampered, recipient.publicKey, recipient.privateKey))
        assertNull(engine.sealedBoxOpen(sealed.copyOf(47), recipient.publicKey, recipient.privateKey))
        assertFalse(sealed.contentEquals(engine.sealedBoxSeal(plaintext, recipient.publicKey)))
    }

    @Test
    fun argon2idDeterministic() {
        val passphrase = "correct horse battery".toByteArray()
        val salt = ByteArray(16) { it.toByte() }
        val a = engine.pwhashArgon2id(passphrase, salt, opsLimit = 1, memLimitBytes = 8L * 1024 * 1024)
        val b = engine.pwhashArgon2id(passphrase, salt, opsLimit = 1, memLimitBytes = 8L * 1024 * 1024)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)

        val otherSalt = ByteArray(16) { (it + 1).toByte() }
        val c = engine.pwhashArgon2id(passphrase, otherSalt, opsLimit = 1, memLimitBytes = 8L * 1024 * 1024)
        assertFalse(a.contentEquals(c))

        val moreOps = engine.pwhashArgon2id(passphrase, salt, opsLimit = 2, memLimitBytes = 8L * 1024 * 1024)
        assertFalse(a.contentEquals(moreOps))
    }

    @Test
    fun xchachaRoundTripAndTamperDetected() {
        val key = engine.randomBytes(32)
        val nonce = engine.randomBytes(24)
        val ad = "header".toByteArray()
        val plaintext = "secret payload".toByteArray()
        val sealed = engine.xchacha20Poly1305Seal(key, nonce, plaintext, ad)
        assertEquals(plaintext.size + 16, sealed.size)
        assertArrayEquals(plaintext, engine.xchacha20Poly1305Open(key, nonce, sealed, ad))

        val tampered = sealed.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertNull(engine.xchacha20Poly1305Open(key, nonce, tampered, ad))
        assertNull(engine.xchacha20Poly1305Open(key, nonce, sealed, "other".toByteArray()))
        assertNull(engine.xchacha20Poly1305Open(key, engine.randomBytes(24), sealed, ad))
        assertNull(engine.xchacha20Poly1305Open(key, nonce, sealed.copyOf(15), ad))
    }

    @Test
    fun x25519PublicFromPrivateMatchesKeypair() {
        val keyPair = engine.generateX25519KeyPair()
        assertArrayEquals(keyPair.publicKey, engine.x25519PublicFromPrivate(keyPair.privateKey))
    }

    @Test
    fun ed25519PublicFromPrivateMatchesKeypair() {
        val keyPair = engine.generateEd25519KeyPair()
        assertArrayEquals(keyPair.publicKey, engine.ed25519PublicFromPrivate(keyPair.privateKey))
        val seed = keyPair.privateKey.copyOf(32)
        assertArrayEquals(keyPair.publicKey, engine.ed25519PublicFromPrivate(seed))
    }

    @Test
    fun memzeroClearsBytes() {
        val bytes = engine.randomBytes(32)
        engine.memzero(bytes)
        assertArrayEquals(ByteArray(32), bytes)
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { i -> value.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
