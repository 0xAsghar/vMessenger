package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A "I removed you" that has not been delivered yet.
 *
 * Deliberately outlives the contact row it is about. The revoke used to be sent once, bounded at
 * ten seconds and never retried — and people delete contacts they are *not* currently talking to,
 * so the common case was that the peer was offline and never found out. They then kept us
 * approved forever, their messages were silently dropped by the inbound policy, and their own
 * outbox showed a single tick that would never become two.
 *
 * It therefore carries its own copy of everything needed to dial the peer, since by the time it is
 * retried the contact is gone. No display name, no history, nothing about the relationship beyond
 * the keys and the request id: it exists to say one word and then delete itself, and the secure
 * wipe drops the table with the rest.
 */
@Entity(tableName = "pending_revoke")
data class PendingRevokeEntity(
    @PrimaryKey val identityHash: ByteArray,
    val ed25519Public: ByteArray,
    val x25519StaticPublic: ByteArray?,
    /** The id of *their* request to us, which is what the receiver validates a response against. */
    val requestId: String,
    val createdAtUnixMs: Long,
    val attemptCount: Int = 0,
    val nextAttemptUnixMs: Long = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is PendingRevokeEntity &&
            identityHash.contentEquals(other.identityHash) &&
            ed25519Public.contentEquals(other.ed25519Public) &&
            (x25519StaticPublic ?: EMPTY).contentEquals(other.x25519StaticPublic ?: EMPTY) &&
            requestId == other.requestId &&
            createdAtUnixMs == other.createdAtUnixMs &&
            attemptCount == other.attemptCount &&
            nextAttemptUnixMs == other.nextAttemptUnixMs

    override fun hashCode(): Int {
        var result = identityHash.contentHashCode()
        result = 31 * result + ed25519Public.contentHashCode()
        result = 31 * result + (x25519StaticPublic?.contentHashCode() ?: 0)
        result = 31 * result + requestId.hashCode()
        result = 31 * result + createdAtUnixMs.hashCode()
        result = 31 * result + attemptCount
        result = 31 * result + nextAttemptUnixMs.hashCode()
        return result
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
