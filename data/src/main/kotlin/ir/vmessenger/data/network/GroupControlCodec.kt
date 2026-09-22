package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.database.entity.GroupMemberEntity
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.core.proto.app.v1.GroupControl
import ir.vmessenger.core.proto.app.v1.GroupControlType
import ir.vmessenger.core.proto.app.v1.GroupMember
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import java.util.UUID
import ir.vmessenger.core.proto.app.v1.GroupMemberRole as ProtoGroupMemberRole

/** Maximum members of a group, the user included. Fan-out is O(n) per message, so it is bounded. */
const val MAX_GROUP_MEMBERS = 100

/**
 * The wire form of a membership change, and the reverse.
 *
 * A control always carries the version it moves the group *to*, and the
 * structural ones carry the full member list: with no server to reconcile
 * against, a receiver that missed a control has to be able to recover from the
 * next snapshot rather than guess what changed.
 */
object GroupControlCodec {
    /**
     * Builds a control envelope. The same bytes go to every recipient, which is
     * why the outbox can store one envelope for the whole fan-out.
     */
    @Suppress("LongParameterList") // a control is exactly these fields; a wrapper would only rename them
    fun envelope(
        selfIdentityHash: ByteArray,
        group: GroupEntity,
        type: GroupControlType,
        members: List<GroupMemberEntity>,
        version: Long,
        targetIdentityHash: String? = null,
        targetRole: GroupMemberRole? = null,
    ): MessageEnvelope {
        val control = GroupControl.newBuilder()
            .setGroupId(ByteString.copyFromUtf8(group.id))
            .setType(type)
            .setName(group.name)
            .setVersion(version)
            .setCreatorIdentityHash(ByteString.copyFromUtf8(group.creatorIdentityHash))
            .setAtUnixMs(System.currentTimeMillis())
            .addAllMembers(members.map(::toProto))
            // On every control, not only the one that changes it: a device that missed a message
            // must not be left applying a stale retention policy.
            .setAuditRetention(group.auditRetention)
        targetIdentityHash?.let { control.targetIdentityHash = ByteString.copyFromUtf8(it) }
        targetRole?.let { control.targetRole = it.toProto() }
        return MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
            .setSenderIdentityHash(ByteString.copyFrom(selfIdentityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setGroupId(ByteString.copyFromUtf8(group.id))
            .setGroupControl(control)
            .build()
    }

    private fun toProto(member: GroupMemberEntity): GroupMember = GroupMember.newBuilder()
        .setIdentityHash(ByteString.copyFromUtf8(member.identityHash))
        .setIdentityPub(ByteString.copyFrom(member.identityPub))
        .setDisplayName(member.displayName)
        .setX25519StaticPub(ByteString.copyFrom(member.x25519StaticPub ?: ByteArray(0)))
        .setRole(member.role.toProto())
        .build()

    private fun GroupMemberRole.toProto(): ProtoGroupMemberRole = when (this) {
        GroupMemberRole.CREATOR -> ProtoGroupMemberRole.GROUP_MEMBER_ROLE_CREATOR
        GroupMemberRole.ADMIN -> ProtoGroupMemberRole.GROUP_MEMBER_ROLE_ADMIN
        GroupMemberRole.MEMBER -> ProtoGroupMemberRole.GROUP_MEMBER_ROLE_MEMBER
    }

    /**
     * The creator's own row always reads CREATOR regardless of what the snapshot said, and an
     * unrecognised or unset role reads MEMBER. Both are fail-closed: a peer cannot promote itself
     * by editing a snapshot it forwards, and a 1.1.2 snapshot — which carries no roles at all —
     * produces a group of plain members rather than a group of admins.
     */
    private fun roleOf(member: GroupMember, hash: String, creatorHash: String): GroupMemberRole = when {
        hash == creatorHash -> GroupMemberRole.CREATOR
        member.role == ProtoGroupMemberRole.GROUP_MEMBER_ROLE_ADMIN -> GroupMemberRole.ADMIN
        else -> GroupMemberRole.MEMBER
    }

    /**
     * The members of a snapshot, as rows. A member with an unusable identity key
     * is dropped rather than stored: we could never open a session to them, and a
     * row we cannot address would only look like a member who never receives
     * anything.
     */
    fun members(control: GroupControl, groupId: String, creatorHash: String, now: Long): List<GroupMemberEntity> =
        control.membersList.mapNotNull { member ->
            val hash = member.identityHash.toStringUtf8().takeIf { it.length == ROUTING_KEY_HEX_LENGTH }
            val identityPub = member.identityPub.toByteArray().takeIf { it.size == ED25519_KEY_SIZE }
            if (hash == null || identityPub == null) {
                null
            } else {
                GroupMemberEntity(
                    groupId = groupId,
                    identityHash = hash,
                    identityPub = identityPub,
                    x25519StaticPub = member.x25519StaticPub.toByteArray().takeIf { it.size == X25519_KEY_SIZE },
                    displayName = member.displayName.take(MAX_NAME_LENGTH),
                    role = roleOf(member, hash, creatorHash),
                    joinedAtUnixMs = now,
                    removedAtUnixMs = null,
                )
            }
        }

    /** The group id an envelope belongs to, or null when it is a 1:1 envelope or the id is malformed. */
    fun groupIdOf(envelope: MessageEnvelope): String? =
        envelope.groupId.toStringUtf8().takeIf { it.length == GROUP_ID_HEX_LENGTH && it.all(Char::isLetterOrDigit) }

    /** A fresh group id: 16 random bytes as lowercase hex, the same shape as a routing key. */
    fun newGroupId(random: ByteArray): String = IdentityHashMatcher.routingKeyHex(random)

    const val MAX_NAME_LENGTH = 64
    private const val ROUTING_KEY_HEX_LENGTH = 32
    private const val GROUP_ID_HEX_LENGTH = 32
    private const val ED25519_KEY_SIZE = 32
    private const val X25519_KEY_SIZE = 32
}
