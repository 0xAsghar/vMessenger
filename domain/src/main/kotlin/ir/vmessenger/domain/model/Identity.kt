package ir.vmessenger.domain.model

data class Identity(
    val ed25519PublicKey: ByteArray,
    val identityHash: ByteArray,
    val userHash: String,
    val x25519StaticPublicKey: ByteArray,
    val createdAtUnixMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Identity
        return ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
            identityHash.contentEquals(other.identityHash) &&
            userHash == other.userHash &&
            x25519StaticPublicKey.contentEquals(other.x25519StaticPublicKey) &&
            createdAtUnixMs == other.createdAtUnixMs
    }

    override fun hashCode(): Int {
        var result = ed25519PublicKey.contentHashCode()
        result = 31 * result + identityHash.contentHashCode()
        result = 31 * result + userHash.hashCode()
        result = 31 * result + x25519StaticPublicKey.contentHashCode()
        result = 31 * result + createdAtUnixMs.hashCode()
        return result
    }
}

data class KeyMaterial(
    val alias: String,
    val wrappedPrivateKey: ByteArray,
    val updatedAtUnixMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KeyMaterial
        return alias == other.alias &&
            wrappedPrivateKey.contentEquals(other.wrappedPrivateKey) &&
            updatedAtUnixMs == other.updatedAtUnixMs
    }

    override fun hashCode(): Int =
        31 * (31 * alias.hashCode() + wrappedPrivateKey.contentHashCode()) + updatedAtUnixMs.hashCode()
}
