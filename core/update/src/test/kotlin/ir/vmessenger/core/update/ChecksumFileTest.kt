package ir.vmessenger.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecksumFileTest {
    private val arm64Digest = "a".repeat(64)
    private val universalDigest = "b".repeat(64)

    @Test
    fun readsASha256sumsFile() {
        val content = """
            $arm64Digest  vMessenger-1.0.0-arm64-v8a.apk
            $universalDigest  vMessenger-1.0.0-universal.apk
        """.trimIndent()

        assertEquals(arm64Digest, ChecksumFile.digestFor(content, "vMessenger-1.0.0-arm64-v8a.apk"))
        assertEquals(universalDigest, ChecksumFile.digestFor(content, "vMessenger-1.0.0-universal.apk"))
    }

    @Test
    fun readsASinglePerAssetFile() {
        val content = "$arm64Digest  vMessenger-1.0.0-arm64-v8a.apk\n"
        assertEquals(arm64Digest, ChecksumFile.digestFor(content, "vMessenger-1.0.0-arm64-v8a.apk"))
    }

    @Test
    fun toleratesCrlfAndBlankLines() {
        val content = "\r\n$arm64Digest  vMessenger-1.0.0-arm64-v8a.apk\r\n\r\n"
        assertEquals(arm64Digest, ChecksumFile.digestFor(content, "vMessenger-1.0.0-arm64-v8a.apk"))
    }

    @Test
    fun toleratesTheBinaryMarkerAndTabs() {
        val content = "$arm64Digest *vMessenger-1.0.0-arm64-v8a.apk\n$universalDigest\tvMessenger-1.0.0-universal.apk"
        assertEquals(arm64Digest, ChecksumFile.digestFor(content, "vMessenger-1.0.0-arm64-v8a.apk"))
        assertEquals(universalDigest, ChecksumFile.digestFor(content, "vMessenger-1.0.0-universal.apk"))
    }

    @Test
    fun stripsADirectoryPrefixFromTheName() {
        val content = "$arm64Digest  ./dist/vMessenger-1.0.0-arm64-v8a.apk"
        assertEquals(arm64Digest, ChecksumFile.digestFor(content, "vMessenger-1.0.0-arm64-v8a.apk"))
    }

    @Test
    fun normalisesTheDigestToLowerCase() {
        val content = "${arm64Digest.uppercase()}  vMessenger-1.0.0-arm64-v8a.apk"
        assertEquals(arm64Digest, ChecksumFile.digestFor(content, "vMessenger-1.0.0-arm64-v8a.apk"))
    }

    @Test
    fun anAssetTheFileDoesNotNameHasNoDigest() {
        val content = "$arm64Digest  vMessenger-1.0.0-arm64-v8a.apk"
        assertNull(ChecksumFile.digestFor(content, "vMessenger-1.0.0-universal.apk"))
    }

    @Test
    fun ignoresLinesThatAreNotDigests() {
        val content = """
            # produced by sha256sum
            not-a-digest  vMessenger-1.0.0-arm64-v8a.apk
            ${"c".repeat(63)}  vMessenger-1.0.0-arm64-v8a.apk
            $arm64Digest  vMessenger-1.0.0-arm64-v8a.apk
        """.trimIndent()

        assertEquals(1, ChecksumFile.parse(content).size)
        assertEquals(arm64Digest, ChecksumFile.digestFor(content, "vMessenger-1.0.0-arm64-v8a.apk"))
    }

    @Test
    fun anEmptyFileParsesToNothing() {
        assertEquals(emptyMap<String, String>(), ChecksumFile.parse(""))
    }
}
