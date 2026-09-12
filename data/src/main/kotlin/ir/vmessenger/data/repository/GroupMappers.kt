package ir.vmessenger.data.repository

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.database.entity.GroupMemberEntity
import ir.vmessenger.domain.model.ContactRelationshipStatus
import ir.vmessenger.domain.model.Group
import ir.vmessenger.domain.model.GroupMember
import ir.vmessenger.domain.model.GroupMemberRole
import ir.vmessenger.core.database.entity.ContactRelationshipStatus as DbContactRelationshipStatus
import ir.vmessenger.core.database.entity.GroupMemberRole as DbGroupMemberRole

/**
 * Group rows to domain models. Kept apart from [GroupRepositoryImpl] so the
 * projections — in particular "is this me" and "is this member a contact", which
 * decide what the group screen offers — can be asserted without a database.
 */

internal fun GroupEntity.toDomain(selfKey: String?): Group = Group(
    id = id,
    name = name,
    creatorIdentityHash = creatorIdentityHash,
    createdAtUnixMs = createdAtUnixMs,
    version = version,
    closed = closed,
    avatarSeed = avatarSeed,
    isCreatedByMe = selfKey != null && creatorIdentityHash == selfKey,
)

/**
 * [contact] is the user's own row for this member, when they have one. Its name
 * wins over the one in the group snapshot: what the user called someone is more
 * useful to them than what that person calls themselves.
 */
internal fun GroupMemberEntity.toDomain(selfKey: String?, contact: ContactEntity?): GroupMember = GroupMember(
    identityHash = identityHash,
    displayName = contact?.displayName?.ifBlank { null } ?: displayName,
    role = role.toDomain(),
    joinedAtUnixMs = joinedAtUnixMs,
    contactId = contact?.id,
    contactStatus = contact?.relationshipStatus?.toDomain(),
    isMe = selfKey != null && identityHash == selfKey,
)

/** A contact as a member of [groupId]: the keys travel with the group so fan-out never needs the contact row. */
internal fun ContactEntity.toMember(groupId: String, now: Long): GroupMemberEntity = GroupMemberEntity(
    groupId = groupId,
    identityHash = IdentityHashMatcher.routingKeyHex(identityHash),
    identityPub = ed25519Public,
    x25519StaticPub = x25519StaticPublic,
    displayName = displayName,
    role = DbGroupMemberRole.MEMBER,
    joinedAtUnixMs = now,
    removedAtUnixMs = null,
)

private fun DbGroupMemberRole.toDomain(): GroupMemberRole = when (this) {
    DbGroupMemberRole.CREATOR -> GroupMemberRole.CREATOR
    DbGroupMemberRole.MEMBER -> GroupMemberRole.MEMBER
}

private fun DbContactRelationshipStatus.toDomain(): ContactRelationshipStatus = when (this) {
    DbContactRelationshipStatus.APPROVED -> ContactRelationshipStatus.APPROVED
    DbContactRelationshipStatus.PENDING_OUT -> ContactRelationshipStatus.PENDING_OUT
    DbContactRelationshipStatus.PENDING_IN -> ContactRelationshipStatus.PENDING_IN
    DbContactRelationshipStatus.REJECTED -> ContactRelationshipStatus.REJECTED
}
