package ir.vmessenger.domain.model

data class Contact(
    val id: String,
    val identityHash: ByteArray,
    val ed25519PublicKey: ByteArray,
    val userHash: String,
    val displayName: String,
    val verified: Boolean,
    val blocked: Boolean,
    val createdAtUnixMs: Long,
    val lastSeenUnixMs: Long?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Contact
        return id == other.id &&
            identityHash.contentEquals(other.identityHash) &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
            userHash == other.userHash &&
            displayName == other.displayName &&
            verified == other.verified &&
            blocked == other.blocked &&
            createdAtUnixMs == other.createdAtUnixMs &&
            lastSeenUnixMs == other.lastSeenUnixMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + identityHash.contentHashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        result = 31 * result + userHash.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + verified.hashCode()
        result = 31 * result + blocked.hashCode()
        result = 31 * result + createdAtUnixMs.hashCode()
        result = 31 * result + (lastSeenUnixMs?.hashCode() ?: 0)
        return result
    }
}
