package ir.vmessenger.core.crypto.keystore

/**
 * Envelope of every Keystore-wrapped secret: `0x02 ‖ iv(12) ‖ ciphertext`.
 *
 * The leading version byte makes the format self-describing, so a future
 * change of cipher or IV length can be told apart from today's blobs instead
 * of being fed to the wrong decryptor. Blobs without the version byte (the
 * unversioned `iv ‖ ciphertext` of 0.x dev builds) are **rejected**: no
 * released build ever carried real user data, so silently guessing the layout
 * would only risk decrypting attacker-chosen bytes.
 *
 * The associated data is derived from the alias ([aad]), which binds a blob to
 * the purpose it was wrapped for: a database blob cannot be unwrapped as an
 * attachment key, nor an identity key as either.
 */
internal object WrappedKeyBlob {
    const val VERSION: Byte = 0x02
    const val IV_LENGTH = 12

    /** GCM tag length; a blob shorter than version + iv + tag cannot be genuine. */
    private const val TAG_LENGTH = 16
    private const val AAD_PREFIX = "vmessenger:"

    class Parsed(val iv: ByteArray, val ciphertext: ByteArray)

    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_LENGTH) { "wrapped blob iv must be $IV_LENGTH bytes, was ${iv.size}" }
        return byteArrayOf(VERSION) + iv + ciphertext
    }

    /** @throws IllegalArgumentException when [wrapped] is not a current, well-formed blob. */
    fun decode(wrapped: ByteArray): Parsed {
        require(wrapped.isNotEmpty()) { "wrapped blob is empty" }
        require(wrapped[0] == VERSION) {
            "unsupported wrapped-key format 0x${"%02x".format(wrapped[0].toInt() and 0xff)}; " +
                "expected 0x${"%02x".format(VERSION.toInt() and 0xff)}"
        }
        require(wrapped.size >= 1 + IV_LENGTH + TAG_LENGTH) { "wrapped blob is truncated (${wrapped.size} bytes)" }
        return Parsed(
            iv = wrapped.copyOfRange(1, 1 + IV_LENGTH),
            ciphertext = wrapped.copyOfRange(1 + IV_LENGTH, wrapped.size),
        )
    }

    fun aad(alias: String): ByteArray = (AAD_PREFIX + alias).toByteArray(Charsets.UTF_8)
}
