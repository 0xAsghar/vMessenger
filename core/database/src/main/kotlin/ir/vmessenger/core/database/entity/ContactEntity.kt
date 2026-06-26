package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact",
    indices = [Index(value = ["identityHash"], unique = true)],
)
data class ContactEntity(
    @PrimaryKey val id: String,
    val identityHash: ByteArray,
    val ed25519Public: ByteArray,
    val x25519StaticPublic: ByteArray? = null,
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
        other as ContactEntity
        return id == other.id &&
            identityHash.contentEquals(other.identityHash) &&
            ed25519Public.contentEquals(other.ed25519Public) &&
            x25519StaticPublic.contentEqualsOrBothNull(other.x25519StaticPublic) &&
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
        result = 31 * result + ed25519Public.contentHashCode()
        result = 31 * result + (x25519StaticPublic?.contentHashCode() ?: 0)
        result = 31 * result + userHash.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + verified.hashCode()
        result = 31 * result + blocked.hashCode()
        result = 31 * result + createdAtUnixMs.hashCode()
        result = 31 * result + (lastSeenUnixMs?.hashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this != null && other != null -> this.contentEquals(other)
            else -> false
        }
}
