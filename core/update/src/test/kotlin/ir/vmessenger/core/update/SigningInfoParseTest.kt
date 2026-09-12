package ir.vmessenger.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningInfoParseTest {
    private val digest = "1f".repeat(32)
    private val otherDigest = "2e".repeat(32)

    /** The shape `apksigner verify --print-certs` writes into the release's SIGNING.txt. */
    private val signingTxt = """
        == vMessenger-1.0.0-arm64-v8a.apk
        Signer #1 certificate DN: CN=vMessenger, O=vMessenger
        Signer #1 certificate SHA-256 digest: $digest
        Signer #1 certificate SHA-1 digest: ${"ab".repeat(10)}
        Signer #1 certificate MD5 digest: ${"cd".repeat(8)}
        == vMessenger-1.0.0-universal.apk
        Signer #1 certificate DN: CN=vMessenger, O=vMessenger
        Signer #1 certificate SHA-256 digest: $digest
    """.trimIndent()

    @Test
    fun readsOneDigestPerApkBlock() {
        val parsed = SigningInfo.parse(signingTxt)
        assertEquals(2, parsed.size)
        assertEquals(digest, parsed["vMessenger-1.0.0-arm64-v8a.apk"])
        assertEquals(digest, parsed["vMessenger-1.0.0-universal.apk"])
    }

    @Test
    fun findsTheDigestForANamedAsset() {
        assertEquals(digest, SigningInfo.digestFor(signingTxt, "vMessenger-1.0.0-universal.apk"))
    }

    @Test
    fun fallsBackToTheFirstBlockForAnUnlistedAsset() {
        // The release workflow refuses to publish splits signed by different keys,
        // so any block answers "who signed this release".
        assertEquals(digest, SigningInfo.digestFor(signingTxt, "vMessenger-1.0.0-x86.apk"))
    }

    @Test
    fun toleratesCrlfAndIndentation() {
        val content = "== a.apk\r\n   Signer #1 certificate SHA-256 digest: $digest   \r\n"
        assertEquals(digest, SigningInfo.digestFor(content, "a.apk"))
    }

    @Test
    fun ignoresTheOtherDigestLines() {
        val content = """
            == a.apk
            Signer #1 certificate SHA-1 digest: ${"ab".repeat(10)}
            Signer #2 certificate SHA-256 digest: $otherDigest
        """.trimIndent()
        assertNull(SigningInfo.digestFor(content, "a.apk"))
    }

    @Test
    fun aFileWithoutAnySignerHasNoDigest() {
        assertNull(SigningInfo.digestFor("DOES NOT VERIFY\n", "a.apk"))
        assertNull(SigningInfo.digestFor("", "a.apk"))
    }

    @Test
    fun comparisonIgnoresCaseAndColonSeparators() {
        val colonSeparated = digest.chunked(2).joinToString(":").uppercase()
        assertTrue(SigningInfo.matches(colonSeparated, digest))
        assertTrue(SigningInfo.matches(digest.uppercase(), digest))
    }

    @Test
    fun comparisonFailsClosedOnAMissingDigest() {
        assertFalse(SigningInfo.matches(null, digest))
        assertFalse(SigningInfo.matches(digest, null))
        assertFalse(SigningInfo.matches("", ""))
        assertFalse(SigningInfo.matches(null, null))
    }

    @Test
    fun differentSignersDoNotMatch() {
        assertFalse(SigningInfo.matches(digest, otherDigest))
    }
}
