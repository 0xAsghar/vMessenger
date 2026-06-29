package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Phase 5: durable embedded DHT record storage with size limits. */
@Entity(tableName = "dht_record")
data class DhtRecordEntity(
    @PrimaryKey val recordKey: String,
    val recordProto: ByteArray,
    val sequence: Long,
    val expiresAtUnixMs: Long,
    val storedAtUnixMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DhtRecordEntity
        return recordKey == other.recordKey &&
            recordProto.contentEquals(other.recordProto) &&
            sequence == other.sequence &&
            expiresAtUnixMs == other.expiresAtUnixMs &&
            storedAtUnixMs == other.storedAtUnixMs
    }

    override fun hashCode(): Int {
        var result = recordKey.hashCode()
        result = 31 * result + recordProto.contentHashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + expiresAtUnixMs.hashCode()
        result = 31 * result + storedAtUnixMs.hashCode()
        return result
    }
}
