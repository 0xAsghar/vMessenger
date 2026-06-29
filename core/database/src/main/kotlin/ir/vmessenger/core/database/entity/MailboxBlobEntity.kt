package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Phase 8: encrypted offline blob awaiting delivery to a recipient identity hash. */
@Entity(
    tableName = "mailbox_blob",
    indices = [Index("recipientIdentityHash"), Index("expiresAtUnixMs")],
)
data class MailboxBlobEntity(
    @PrimaryKey val blobId: String,
    val recipientIdentityHash: ByteArray,
    val sealedPayload: ByteArray,
    val expiresAtUnixMs: Long,
    val createdAtUnixMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MailboxBlobEntity
        return blobId == other.blobId &&
            recipientIdentityHash.contentEquals(other.recipientIdentityHash) &&
            sealedPayload.contentEquals(other.sealedPayload) &&
            expiresAtUnixMs == other.expiresAtUnixMs &&
            createdAtUnixMs == other.createdAtUnixMs
    }

    override fun hashCode(): Int {
        var result = blobId.hashCode()
        result = 31 * result + recipientIdentityHash.contentHashCode()
        result = 31 * result + sealedPayload.contentHashCode()
        result = 31 * result + expiresAtUnixMs.hashCode()
        result = 31 * result + createdAtUnixMs.hashCode()
        return result
    }
}
