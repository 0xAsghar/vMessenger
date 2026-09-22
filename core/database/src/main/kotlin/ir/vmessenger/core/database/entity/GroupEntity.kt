package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A group chat as this device knows it.
 *
 * [id] is the wire `group_id` (16 random bytes) in lowercase hex, so the same
 * string identifies the group locally and on the wire. Membership is
 * creator-authoritative: every structural change is a `GroupControl` from
 * [creatorIdentityHash] carrying [version] = ours + 1, so a device that misses
 * one control notices the gap instead of silently diverging.
 *
 * Identity hashes are stored as lowercase hex here and in every table added with
 * groups. The legacy columns (`contact.identityHash`) stay BLOB; the new ones are
 * TEXT because they are primary-key and JOIN columns, where a BLOB is awkward to
 * compare, log and re-key in a migration.
 */
@Entity(tableName = "chat_group")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val creatorIdentityHash: String,
    val createdAtUnixMs: Long,
    /** Creator-assigned revision of the membership; only accepted going forward. */
    val version: Long,
    /** True once the creator closed the group, or we were removed from it: read-only from then on. */
    val closed: Boolean,
    /** Identicon seed for the group avatar; derived from [id] at creation and never changes. */
    val avatarSeed: String,
    /**
     * Whether admins of this group may review messages that were edited or deleted.
     *
     * Creator-authored and carried in every membership snapshot, so it cannot diverge between
     * devices — a member's copy is whatever the creator last said, not a local preference. **Off by
     * default, and never set on a 1:1 conversation**, which has no creator and no admins.
     *
     * It reverses a real privacy property, so a group with it on shows every member a banner
     * saying so. See [MessageEditHistoryEntity].
     */
    val auditRetention: Boolean = false,
)

enum class GroupMemberRole {
    CREATOR,

    /**
     * May review this group's edited and deleted messages, when the creator has switched retention
     * on. Designated **only** by the creator, through a version-gated control, so every device
     * records the same admin set from the same authoritative snapshot — there is no server to ask.
     */
    ADMIN,
    MEMBER,
}

/**
 * One member of a group, with the keys needed to reach them directly.
 *
 * A member is not necessarily a contact: the keys travel in the group snapshot so
 * fan-out can address someone the user has not added, and the UI offers to add
 * them. [removedAtUnixMs] is a tombstone rather than a delete, so a late control
 * message for a departed member is recognised instead of re-adding them.
 */
@Entity(
    tableName = "chat_group_member",
    primaryKeys = ["groupId", "identityHash"],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("identityHash")],
)
data class GroupMemberEntity(
    val groupId: String,
    val identityHash: String,
    val identityPub: ByteArray,
    val x25519StaticPub: ByteArray?,
    val displayName: String,
    val role: GroupMemberRole,
    val joinedAtUnixMs: Long,
    val removedAtUnixMs: Long?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GroupMemberEntity
        return scalarFields() == other.scalarFields() &&
            identityPub.contentEquals(other.identityPub) &&
            (x25519StaticPub ?: EMPTY).contentEquals(other.x25519StaticPub ?: EMPTY)
    }

    override fun hashCode(): Int {
        var result = scalarFields().hashCode()
        result = 31 * result + identityPub.contentHashCode()
        result = 31 * result + (x25519StaticPub?.contentHashCode() ?: 0)
        return result
    }

    /** Every field except the byte arrays, so equality/hash keep data-class semantics. */
    private fun scalarFields(): List<Any?> = listOf(
        groupId,
        identityHash,
        displayName,
        role,
        joinedAtUnixMs,
        removedAtUnixMs,
    )

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
