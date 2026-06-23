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

interface CryptoEngine {
    fun generateEd25519KeyPair(): KeyPair
    fun signEd25519(message: ByteArray, privateKey: ByteArray): ByteArray
    fun verifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
    fun generateX25519KeyPair(): KeyPair
    fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    fun seal(plaintext: ByteArray, key: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray
    fun open(ciphertext: ByteArray, key: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray?
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun randomBytes(length: Int): ByteArray
}
