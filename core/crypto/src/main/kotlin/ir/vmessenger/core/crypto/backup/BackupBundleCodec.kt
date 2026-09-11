package ir.vmessenger.core.crypto.backup

import ir.vmessenger.core.crypto.CryptoEngine
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** KDF parameters and format version read from a bundle header (no passphrase needed). */
data class BackupHeaderInfo(
    val version: Int,
    val kdfOps: Int,
    val kdfMemBytes: Long,
)

sealed class BackupBundleException(message: String) : Exception(message) {
    /** Unknown magic/version/algorithm, out-of-range KDF parameters, or truncated input. */
    class Malformed(message: String) : BackupBundleException(message)

    /** AEAD authentication failed: wrong passphrase or a tampered header/ciphertext. */
    class AuthenticationFailed : BackupBundleException("backup bundle authentication failed")
}

/**
 * Passphrase-encrypted `.vmb` bundle, format v1:
 *
 * ```
 * magic "VMB1" (4) | version u8 = 1 | kdfAlg u8 = 1 (argon2id13) | opslimit u32be | memlimit u32be (bytes)
 * | salt (16) | nonce (24) | crypto_aead_xchacha20poly1305_ietf(payload, ad = the 54 header bytes, key)
 * key = crypto_pwhash(32, NFC(passphrase) as UTF-8, salt, opslimit, memlimit, ARGON2ID13)
 * ```
 *
 * The header is the AEAD associated data, so the KDF parameters cannot be downgraded without failing
 * authentication. Pure JVM code: no Android dependencies.
 *
 * The caller owns [CharArray] passphrases and must clear them; the codec zeroizes its own UTF-8 copy and the
 * derived key. NFC normalization goes through [Normalizer], which materializes an intermediate [String] that
 * cannot be wiped.
 */
@Singleton
class BackupBundleCodec @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    /** Encrypts [payloadBytes] under [passphrase]; the returned bundle is safe to persist as-is. */
    fun encode(
        payloadBytes: ByteArray,
        passphrase: CharArray,
        opsLimit: Int = DEFAULT_OPS_LIMIT,
        memLimitBytes: Long = DEFAULT_MEM_LIMIT_BYTES,
    ): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_CHARS) { "passphrase must be at least $MIN_PASSPHRASE_CHARS chars" }
        require(opsLimit in OPS_LIMIT_RANGE) { "opsLimit out of range: $opsLimit" }
        require(memLimitBytes in MEM_LIMIT_RANGE) { "memLimitBytes out of range: $memLimitBytes" }
        val salt = cryptoEngine.randomBytes(SALT_SIZE)
        val nonce = cryptoEngine.randomBytes(NONCE_SIZE)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC)
            .put(FORMAT_VERSION.toByte())
            .put(KDF_ALG_ARGON2ID13.toByte())
            .putInt(opsLimit)
            .putInt(memLimitBytes.toInt())
            .put(salt)
            .put(nonce)
            .array()
        val key = deriveKey(passphrase, salt, opsLimit.toLong(), memLimitBytes)
        try {
            val ciphertext = cryptoEngine.xchacha20Poly1305Seal(key, nonce, payloadBytes, header)
            return header + ciphertext
        } finally {
            cryptoEngine.memzero(key)
        }
    }

    /**
     * Decrypts [bundle] with [passphrase].
     *
     * @throws BackupBundleException.Malformed for an unrecognized or truncated bundle.
     * @throws BackupBundleException.AuthenticationFailed for a wrong passphrase or a tampered bundle.
     */
    fun decode(bundle: ByteArray, passphrase: CharArray): ByteArray {
        val header = parseHeader(bundle)
        if (bundle.size < HEADER_SIZE + TAG_SIZE) throw BackupBundleException.Malformed("bundle truncated")
        val headerBytes = bundle.copyOfRange(0, HEADER_SIZE)
        val salt = bundle.copyOfRange(SALT_OFFSET, SALT_OFFSET + SALT_SIZE)
        val nonce = bundle.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_SIZE)
        val ciphertext = bundle.copyOfRange(HEADER_SIZE, bundle.size)
        val key = deriveKey(passphrase, salt, header.kdfOps.toLong(), header.kdfMemBytes)
        try {
            return cryptoEngine.xchacha20Poly1305Open(key, nonce, ciphertext, headerBytes)
                ?: throw BackupBundleException.AuthenticationFailed()
        } finally {
            cryptoEngine.memzero(key)
        }
    }

    /** Reads the header without a passphrase; validates magic, version, algorithm and KDF parameter ranges. */
    fun inspect(bundle: ByteArray): BackupHeaderInfo = parseHeader(bundle)

    private fun parseHeader(bundle: ByteArray): BackupHeaderInfo {
        requireFormat(bundle.size >= HEADER_SIZE) { "bundle shorter than header" }
        val buffer = ByteBuffer.wrap(bundle, 0, HEADER_SIZE)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        requireFormat(magic.contentEquals(MAGIC)) { "not a vMessenger backup bundle" }
        val version = buffer.get().toInt() and 0xFF
        requireFormat(version == FORMAT_VERSION) { "unsupported bundle version $version" }
        val kdfAlg = buffer.get().toInt() and 0xFF
        requireFormat(kdfAlg == KDF_ALG_ARGON2ID13) { "unsupported kdf algorithm $kdfAlg" }
        val opsLimit = buffer.getInt().toLong() and 0xFFFF_FFFFL
        val memLimit = buffer.getInt().toLong() and 0xFFFF_FFFFL
        requireFormat(opsLimit in OPS_LIMIT_RANGE.first.toLong()..OPS_LIMIT_RANGE.last.toLong()) {
            "kdf opslimit out of range: $opsLimit"
        }
        requireFormat(memLimit in MEM_LIMIT_RANGE) { "kdf memlimit out of range: $memLimit" }
        return BackupHeaderInfo(version = version, kdfOps = opsLimit.toInt(), kdfMemBytes = memLimit)
    }

    private inline fun requireFormat(condition: Boolean, message: () -> String) {
        if (!condition) throw BackupBundleException.Malformed(message())
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long): ByteArray {
        val passphraseBytes = passphraseUtf8(passphrase)
        try {
            return cryptoEngine.pwhashArgon2id(passphraseBytes, salt, opsLimit, memLimitBytes, KEY_SIZE)
        } finally {
            cryptoEngine.memzero(passphraseBytes)
        }
    }

    private fun passphraseUtf8(passphrase: CharArray): ByteArray {
        val normalized = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC)
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(normalized))
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        encoded.clear()
        while (encoded.hasRemaining()) encoded.put(0)
        return bytes
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val KDF_ALG_ARGON2ID13 = 1
        const val DEFAULT_OPS_LIMIT = 3
        const val DEFAULT_MEM_LIMIT_BYTES = 64L * 1024 * 1024
        const val MIN_PASSPHRASE_CHARS = 8
        const val HEADER_SIZE = 54
        const val SALT_SIZE = 16
        const val NONCE_SIZE = 24
        const val KEY_SIZE = 32
        const val TAG_SIZE = 16
        const val OPS_LIMIT_OFFSET = 6
        const val MEM_LIMIT_OFFSET = 10
        const val SALT_OFFSET = 14
        const val NONCE_OFFSET = 30
        val OPS_LIMIT_RANGE = 1..10

        /**
         * Accepted `memlimit` on decode. The upper bound is what the app is willing to allocate natively for an
         * untrusted header: 256 MiB (`crypto_pwhash_MEMLIMIT_MODERATE`) is already the ceiling low-end phones
         * survive, so anything above is rejected as [BackupBundleException.Malformed] rather than attempted.
         */
        val MEM_LIMIT_RANGE = (8L * 1024 * 1024)..(256L * 1024 * 1024)
        val MAGIC = byteArrayOf('V'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    }
}
