package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_request",
    indices = [Index("requesterIdentityHash")],
)
data class ContactRequestEntity(
    @PrimaryKey val requestId: String,
    val requesterIdentityHash: ByteArray,
    val requesterUserHash: String,
    val requesterDisplayName: String,
    val requesterEd25519Public: ByteArray,
    val requesterX25519StaticPublic: ByteArray?,
    val receivedAtUnixMs: Long,
    val status: ContactRequestStatus,
    // How many times the user rejected this requester; after enough rejections
    // repeat requests are auto-declined silently instead of re-prompting.
    val rejectCount: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ContactRequestEntity
        return requestId == other.requestId &&
            requesterIdentityHash.contentEquals(other.requesterIdentityHash) &&
            requesterUserHash == other.requesterUserHash &&
            requesterDisplayName == other.requesterDisplayName &&
            requesterEd25519Public.contentEquals(other.requesterEd25519Public) &&
            requesterX25519StaticPublic.contentEqualsOrNull(other.requesterX25519StaticPublic) &&
            receivedAtUnixMs == other.receivedAtUnixMs &&
            status == other.status &&
            rejectCount == other.rejectCount
    }

    override fun hashCode(): Int {
        var result = requestId.hashCode()
        result = 31 * result + requesterIdentityHash.contentHashCode()
        result = 31 * result + requesterUserHash.hashCode()
        result = 31 * result + requesterDisplayName.hashCode()
        result = 31 * result + requesterEd25519Public.contentHashCode()
        result = 31 * result + (requesterX25519StaticPublic?.contentHashCode() ?: 0)
        result = 31 * result + receivedAtUnixMs.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + rejectCount
        return result
    }

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this != null && other != null -> this.contentEquals(other)
            else -> false
        }
}
