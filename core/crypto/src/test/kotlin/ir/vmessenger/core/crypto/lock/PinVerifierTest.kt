package ir.vmessenger.core.crypto.lock

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app-lock PIN check.
 *
 * Deliberately exercised through the real Argon2id rather than a fake: the cost parameters are
 * part of what is being asserted, and a verifier that "works" against a stub would tell us nothing
 * about the one that ships.
 */
class PinVerifierTest {
    private val verifier = PinVerifier(LazysodiumCryptoEngine(LazySodiumJava(SodiumJava())))

    @Test
    fun `the pin that created a verifier opens it`() {
        val created = verifier.create("1234".toCharArray())

        assertTrue(verifier.matches("1234".toCharArray(), created))
    }

    @Test
    fun `a wrong pin does not`() {
        val created = verifier.create("1234".toCharArray())

        assertFalse(verifier.matches("1235".toCharArray(), created))
        assertFalse(verifier.matches("".toCharArray(), created))
        assertFalse(verifier.matches("12345".toCharArray(), created))
    }

    @Test
    fun `two verifiers for the same pin differ, so the stored blob is not a fingerprint of it`() {
        val first = verifier.create("1234".toCharArray())
        val second = verifier.create("1234".toCharArray())

        // Fresh salt and nonce each time: identical PINs must not produce identical stored bytes,
        // or the file itself would reveal that two installs share a PIN.
        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.sealed.contentEquals(second.sealed))
        assertTrue(verifier.matches("1234".toCharArray(), second))
    }

    @Test
    fun `a tampered verifier fails rather than opening`() {
        val created = verifier.create("1234".toCharArray())
        val tampered = created.copy(sealed = created.sealed.copyOf().also { it[0] = (it[0] + 1).toByte() })

        // Authenticated, not compared: flipping a byte makes it unopenable rather than a near miss.
        assertFalse(verifier.matches("1234".toCharArray(), tampered))
    }

    @Test
    fun `an alphanumeric passphrase works as well as digits`() {
        val created = verifier.create("correct horse battery".toCharArray())

        assertTrue(verifier.matches("correct horse battery".toCharArray(), created))
        assertFalse(verifier.matches("correct horse batterz".toCharArray(), created))
    }
}
