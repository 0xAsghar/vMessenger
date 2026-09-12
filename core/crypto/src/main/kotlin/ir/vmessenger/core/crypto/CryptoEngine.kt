package ir.vmessenger.core.crypto

data class KeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KeyPair
        return publicKey.contentEquals(other.publicKey) && privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int = 31 * publicKey.contentHashCode() + privateKey.contentHashCode()
}

@Suppress("TooManyFunctions") // single libsodium facade; every primitive the app needs lives here
interface CryptoEngine {
    fun generateEd25519KeyPair(): KeyPair
    fun signEd25519(message: ByteArray, privateKey: ByteArray): ByteArray
    fun verifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
    fun generateX25519KeyPair(): KeyPair

    /**
     * Raw `crypto_scalarmult` (X25519). Throws [IllegalStateException] when the result is all-zero,
     * i.e. [publicKey] is a low-order point; callers must never derive keys from such a value.
     */
    fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    /** ChaCha20-Poly1305-IETF with a fresh random 12-byte nonce prepended to the ciphertext. */
    fun seal(plaintext: ByteArray, key: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray

    /** Inverse of [seal]; returns null on authentication failure or malformed input. */
    fun open(ciphertext: ByteArray, key: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray?
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun randomBytes(length: Int): ByteArray

    /** Argon2id13 password hash (`crypto_pwhash`); [salt] must be 16 bytes. Caller zeroizes the result. */
    fun pwhashArgon2id(
        passphrase: ByteArray,
        salt: ByteArray,
        opsLimit: Long,
        memLimitBytes: Long,
        keyLen: Int = 32,
    ): ByteArray

    /** `crypto_aead_xchacha20poly1305_ietf_encrypt`; [nonce] is 24 bytes and is NOT prepended to the output. */
    fun xchacha20Poly1305Seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray

    /** `crypto_aead_xchacha20poly1305_ietf_decrypt`; returns null on authentication failure. */
    fun xchacha20Poly1305Open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray?

    /** `crypto_box_seal`: anonymous sealed box to [recipientPublicKey] (32-byte X25519 key). */
    fun sealedBoxSeal(plaintext: ByteArray, recipientPublicKey: ByteArray): ByteArray

    /** `crypto_box_seal_open` with the recipient's X25519 key pair; returns null on failure. */
    fun sealedBoxOpen(ciphertext: ByteArray, recipientPublicKey: ByteArray, recipientPrivateKey: ByteArray): ByteArray?

    /** `crypto_scalarmult_base`: X25519 public key for a 32-byte private scalar. */
    fun x25519PublicFromPrivate(privateKey: ByteArray): ByteArray

    /** `crypto_sign_ed25519_sk_to_pk`: Ed25519 public key for a 64-byte secret key (or 32-byte seed). */
    fun ed25519PublicFromPrivate(privateKey: ByteArray): ByteArray

    /** Best-effort secure wipe (`sodium_memzero`). */
    fun memzero(bytes: ByteArray)
}
