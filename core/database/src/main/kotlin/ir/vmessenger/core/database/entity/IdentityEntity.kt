package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity")
data class IdentityEntity(
    @PrimaryKey val id: Int = 0,
    val ed25519Public: ByteArray,
    val identityHash: ByteArray,
    val userHash: String,
    val x25519StaticPublic: ByteArray,
    val createdAtUnixMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IdentityEntity
        return id == other.id &&
            ed25519Public.contentEquals(other.ed25519Public) &&
            identityHash.contentEquals(other.identityHash) &&
            userHash == other.userHash &&
            x25519StaticPublic.contentEquals(other.x25519StaticPublic) &&
            createdAtUnixMs == other.createdAtUnixMs
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + ed25519Public.contentHashCode()
        result = 31 * result + identityHash.contentHashCode()
        result = 31 * result + userHash.hashCode()
        result = 31 * result + x25519StaticPublic.contentHashCode()
        result = 31 * result + createdAtUnixMs.hashCode()
        return result
    }
}
