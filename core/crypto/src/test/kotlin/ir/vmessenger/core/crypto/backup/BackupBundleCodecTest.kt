package ir.vmessenger.core.crypto.backup

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class BackupBundleCodecTest {
    private lateinit var codec: BackupBundleCodec

    private val payload = "backup payload bytes".toByteArray()
    private val passphrase = "correct horse battery".toCharArray()

    @Before
    fun setUp() {
        codec = BackupBundleCodec(LazysodiumCryptoEngine(LazySodiumJava(SodiumJava())))
    }

    @Test
    fun roundTrip() {
        val bundle = codec.encode(payload, passphrase)
        assertEquals(BackupBundleCodec.HEADER_SIZE + payload.size + BackupBundleCodec.TAG_SIZE, bundle.size)
        assertArrayEquals(BackupBundleCodec.MAGIC, bundle.copyOf(4))

        val info = codec.inspect(bundle)
        assertEquals(BackupBundleCodec.FORMAT_VERSION, info.version)
        assertEquals(BackupBundleCodec.DEFAULT_OPS_LIMIT, info.kdfOps)
        assertEquals(BackupBundleCodec.DEFAULT_MEM_LIMIT_BYTES, info.kdfMemBytes)

        assertArrayEquals(payload, codec.decode(bundle, passphrase))
    }

    @Test
    fun emptyPayloadRoundTrip() {
        val bundle = fastEncode(ByteArray(0))
        assertArrayEquals(ByteArray(0), codec.decode(bundle, passphrase))
    }

    @Test
    fun wrongPassphraseFails() {
        val bundle = fastEncode(payload)
        assertThrows(BackupBundleException.AuthenticationFailed::class.java) {
            codec.decode(bundle, "correct horse battery!".toCharArray())
        }
    }

    @Test
    fun tamperedHeaderParamFails() {
        val bundle = fastEncode(payload)
        val lowOpsByte = BackupBundleCodec.OPS_LIMIT_OFFSET + 3
        assertEquals(FAST_OPS.toByte(), bundle[lowOpsByte])
        // Still a valid opslimit, so the header parses and only the AEAD (header as AD) can catch it.
        bundle[lowOpsByte] = (FAST_OPS + 1).toByte()
        assertEquals(FAST_OPS + 1, codec.inspect(bundle).kdfOps)
        assertThrows(BackupBundleException.AuthenticationFailed::class.java) {
            codec.decode(bundle, passphrase)
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val bundle = fastEncode(payload)
        bundle[bundle.lastIndex] = (bundle.last().toInt() xor 0x01).toByte()
        assertThrows(BackupBundleException.AuthenticationFailed::class.java) {
            codec.decode(bundle, passphrase)
        }
    }

    @Test
    fun unknownMagicOrVersionRejected() {
        val badMagic = fastEncode(payload).also { it[0] = 'X'.code.toByte() }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.inspect(badMagic) }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.decode(badMagic, passphrase) }

        val badVersion = fastEncode(payload).also { it[4] = 2 }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.inspect(badVersion) }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.decode(badVersion, passphrase) }

        val badKdf = fastEncode(payload).also { it[5] = 2 }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.inspect(badKdf) }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.decode(badKdf, passphrase) }
    }

    @Test
    fun absurdKdfParamsRejected() {
        val zeroOps = fastEncode(payload).also { it[BackupBundleCodec.OPS_LIMIT_OFFSET + 3] = 0 }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.inspect(zeroOps) }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.decode(zeroOps, passphrase) }

        val hugeOps = fastEncode(payload).also { it[BackupBundleCodec.OPS_LIMIT_OFFSET] = 0xFF.toByte() }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.inspect(hugeOps) }

        val hugeMem = fastEncode(payload).also {
            // 1 GiB = 0x40000000
            it[BackupBundleCodec.MEM_LIMIT_OFFSET] = 0x40
            it[BackupBundleCodec.MEM_LIMIT_OFFSET + 1] = 0
            it[BackupBundleCodec.MEM_LIMIT_OFFSET + 2] = 0
            it[BackupBundleCodec.MEM_LIMIT_OFFSET + 3] = 0
        }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.inspect(hugeMem) }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.decode(hugeMem, passphrase) }
    }

    @Test
    fun truncatedRejected() {
        val bundle = fastEncode(payload)
        val shorterThanHeader = bundle.copyOf(BackupBundleCodec.HEADER_SIZE - 1)
        assertThrows(BackupBundleException.Malformed::class.java) { codec.inspect(shorterThanHeader) }
        assertThrows(BackupBundleException.Malformed::class.java) { codec.decode(shorterThanHeader, passphrase) }

        val headerOnly = bundle.copyOf(BackupBundleCodec.HEADER_SIZE)
        assertThrows(BackupBundleException.Malformed::class.java) { codec.decode(headerOnly, passphrase) }

        val missingTail = bundle.copyOf(bundle.size - 1)
        assertThrows(BackupBundleException.AuthenticationFailed::class.java) { codec.decode(missingTail, passphrase) }

        assertThrows(BackupBundleException.Malformed::class.java) { codec.decode(ByteArray(0), passphrase) }
    }

    @Test
    fun argon2ParamsReadFromHeaderNotHardcoded() {
        val bundle = codec.encode(payload, passphrase, opsLimit = 2, memLimitBytes = FAST_MEM)
        val info = codec.inspect(bundle)
        assertEquals(2, info.kdfOps)
        assertEquals(FAST_MEM, info.kdfMemBytes)
        assertArrayEquals(payload, codec.decode(bundle, passphrase))
    }

    @Test
    fun passphraseIsNfcNormalized() {
        val decomposed = "cafe\u0301 passphrase".toCharArray()
        val composed = "caf\u00e9 passphrase".toCharArray()
        assertEquals(composed.size + 1, decomposed.size)
        val bundle = codec.encode(payload, decomposed, opsLimit = FAST_OPS, memLimitBytes = FAST_MEM)
        assertArrayEquals(payload, codec.decode(bundle, composed))
    }

    @Test
    fun shortPassphraseRejectedOnEncode() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(payload, "1234567".toCharArray(), opsLimit = FAST_OPS, memLimitBytes = FAST_MEM)
        }
    }

    @Test
    fun outOfRangeParamsRejectedOnEncode() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(payload, passphrase, opsLimit = 0, memLimitBytes = FAST_MEM)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(payload, passphrase, opsLimit = FAST_OPS, memLimitBytes = 1024L * 1024 * 1024)
        }
    }

    /**
     * Bytes produced once by the v1 codec (opslimit 2, memlimit 16 MiB) and committed verbatim: every future
     * codec version must still open a v1 bundle, or backups made today become unreadable after an update.
     */
    @Test
    fun decodesCommittedV1Fixture() {
        val bundle = hex(V1_FIXTURE_HEX)
        val expectedSize = BackupBundleCodec.HEADER_SIZE + V1_FIXTURE_PAYLOAD.length + BackupBundleCodec.TAG_SIZE
        assertEquals(expectedSize, bundle.size)
        val info = codec.inspect(bundle)
        assertEquals(1, info.version)
        assertEquals(2, info.kdfOps)
        assertEquals(16L * 1024 * 1024, info.kdfMemBytes)
        assertArrayEquals(V1_FIXTURE_PAYLOAD.toByteArray(), codec.decode(bundle, V1_FIXTURE_PASSPHRASE.toCharArray()))
        assertThrows(BackupBundleException.AuthenticationFailed::class.java) {
            codec.decode(bundle, "fixture-passphrase-2025".toCharArray())
        }
    }

    private fun fastEncode(bytes: ByteArray): ByteArray =
        codec.encode(bytes, passphrase, opsLimit = FAST_OPS, memLimitBytes = FAST_MEM)

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { i -> value.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private companion object {
        const val FAST_OPS = 1
        const val FAST_MEM = 8L * 1024 * 1024
        const val V1_FIXTURE_PASSPHRASE = "fixture-passphrase-2026"
        const val V1_FIXTURE_PAYLOAD = "vMessenger backup fixture v1: identity+contacts payload stand-in"
        const val V1_FIXTURE_HEX =
            "564d4231010100000002010000004a1a9c7bff615e7a844c3f19bfacf933964f490f0be4b8dc7c4a92eb16111f9b832a" +
                "a53379e847810d23cf9e8809b54305e4f6a1b3f1975bc446b74545d026abc020592a183b8e8639ce9be7637b6eaeaf37" +
                "341fc6b9f5d1b78b7d7e394b42b21df9afac22b039bb9b95d0677d27045b843e4cc9a5122d29"
    }
}
