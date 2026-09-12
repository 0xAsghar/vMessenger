package ir.vmessenger.domain.model

/**
 * A group chat.
 *
 * There is no server, so membership is creator-authoritative and versioned: the
 * creator issues every structural change with [version] one higher than the last,
 * and a device that sees a gap asks for a fresh snapshot instead of diverging.
 * [closed] means the group is read-only here — the creator closed it, we left, or
 * we were removed.
 */
data class Group(
    val id: String,
    val name: String,
    /** Lowercase hex identity hash of the creator; compare with [isCreatedByMe] rather than by hand. */
    val creatorIdentityHash: String,
    val createdAtUnixMs: Long,
    val version: Long,
    val closed: Boolean,
    val avatarSeed: String,
    /** True when this device's identity is the creator's, so the admin actions are available. */
    val isCreatedByMe: Boolean,
)

enum class GroupMemberRole { CREATOR, MEMBER }

/**
 * One member of a group. A member need not be a contact: their keys travel in the
 * group snapshot, so messages reach them either way, and [contactId] is null until
 * the user adds them. [isMe] marks this device's own row.
 */
data class GroupMember(
    val identityHash: String,
    val displayName: String,
    val role: GroupMemberRole,
    val joinedAtUnixMs: Long,
    val contactId: String?,
    val contactStatus: ContactRelationshipStatus?,
    val isMe: Boolean,
) {
    val isContact: Boolean get() = contactId != null
}
