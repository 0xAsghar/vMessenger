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

    /**
     * SHA-1 and MD5 lines are noise. The SHA-256 line is the answer whatever `apksigner`
     * labelled it — the label varies by build-tools version and signature scheme, and an APK
     * is not less signed because its only signer block came out numbered `#2`.
     */
    @Test
    fun ignoresDigestsThatAreNotSha256() {
        val content = """
            == a.apk
            Signer #1 certificate SHA-1 digest: ${"ab".repeat(10)}
            Signer #1 certificate MD5 digest: ${"cd".repeat(8)}
            Signer #2 certificate SHA-256 digest: $otherDigest
        """.trimIndent()
        assertEquals(otherDigest, SigningInfo.digestFor(content, "a.apk"))
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

    /**
     * The exact bytes of the published 1.0.0 `SIGNING.txt`. The runner's `apksigner` prints
     * `V2 Signer:`, not `Signer #1` — a parser written against the latter matched nothing, so
     * every genuine release would have been refused as unsigned. This is here so the real
     * format, not an assumed one, is what the parser is held to.
     */
    @Test
    fun `the published release format parses`() {
        val published = """
            == vMessenger-1.0.0-arm64-v8a.apk
            V2 Signer: certificate DN: CN=Unknown, OU=Unknown, O=Unknown
            V2 Signer: certificate SHA-256 digest: $RELEASE_DIGEST
            V2 Signer: certificate SHA-1 digest: 1de5b3b1af73ae809423fdd2e958f211bb390b97
            == vMessenger-1.0.0-universal.apk
            V2 Signer: certificate SHA-256 digest: $RELEASE_DIGEST
        """.trimIndent()

        assertEquals(RELEASE_DIGEST, SigningInfo.digestFor(published, "vMessenger-1.0.0-arm64-v8a.apk"))
        assertEquals(RELEASE_DIGEST, SigningInfo.digestFor(published, "vMessenger-1.0.0-universal.apk"))
    }

    /** Older build-tools, and a form naming the SDK range; both still name the same field. */
    @Test
    fun `the other shapes apksigner prints parse too`() {
        val shapes = """
            == a.apk
            Signer #1 certificate SHA-256 digest: $RELEASE_DIGEST
            == b.apk
            Signer (minSdkVersion=24, maxSdkVersion=2147483647) #1 certificate SHA-256 digest: $RELEASE_DIGEST
        """.trimIndent()

        assertEquals(RELEASE_DIGEST, SigningInfo.digestFor(shapes, "a.apk"))
        assertEquals(RELEASE_DIGEST, SigningInfo.digestFor(shapes, "b.apk"))
    }

    /**
     * Repeated identical lines are normal (one per signature scheme). Two *different* digests
     * in one block are not: picking either would be a guess, so the asset is refused.
     */
    @Test
    fun `an asset with two different signers is refused rather than guessed`() {
        val conflicting = """
            == a.apk
            V2 Signer: certificate SHA-256 digest: $RELEASE_DIGEST
            V3 Signer: certificate SHA-256 digest: ${"0".repeat(64)}
        """.trimIndent()

        assertNull(SigningInfo.digestFor(conflicting, "a.apk"))
    }

    private companion object {
        const val RELEASE_DIGEST = "31d124e9d4d5c19f22f021f42344867bc8b970cd7d560fe8d84df834a9cbd106"
    }
}
