package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 8: encrypted offline blob awaiting delivery to a recipient identity hash.
 * [senderIdentityHash] is the authenticated session peer that stored it (null for
 * blobs this device queued itself) and drives the per-sender quota.
 */
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
    val senderIdentityHash: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MailboxBlobEntity
        return blobId == other.blobId &&
            recipientIdentityHash.contentEquals(other.recipientIdentityHash) &&
            sealedPayload.contentEquals(other.sealedPayload) &&
            expiresAtUnixMs == other.expiresAtUnixMs &&
            createdAtUnixMs == other.createdAtUnixMs &&
            sameSender(other)
    }

    private fun sameSender(other: MailboxBlobEntity): Boolean {
        val mine = senderIdentityHash
        val theirs = other.senderIdentityHash
        return when {
            mine == null && theirs == null -> true
            mine != null && theirs != null -> mine.contentEquals(theirs)
            else -> false
        }
    }

    override fun hashCode(): Int {
        var result = blobId.hashCode()
        result = 31 * result + recipientIdentityHash.contentHashCode()
        result = 31 * result + sealedPayload.contentHashCode()
        result = 31 * result + expiresAtUnixMs.hashCode()
        result = 31 * result + createdAtUnixMs.hashCode()
        result = 31 * result + (senderIdentityHash?.contentHashCode() ?: 0)
        return result
    }
}
