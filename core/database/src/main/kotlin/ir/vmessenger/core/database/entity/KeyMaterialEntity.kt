package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "key_material")
data class KeyMaterialEntity(
    @PrimaryKey val alias: String,
    val wrappedPrivateKey: ByteArray,
    val updatedAtUnixMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KeyMaterialEntity
        return alias == other.alias &&
            wrappedPrivateKey.contentEquals(other.wrappedPrivateKey) &&
            updatedAtUnixMs == other.updatedAtUnixMs
    }

    override fun hashCode(): Int =
        31 * (31 * alias.hashCode() + wrappedPrivateKey.contentHashCode()) + updatedAtUnixMs.hashCode()
}
