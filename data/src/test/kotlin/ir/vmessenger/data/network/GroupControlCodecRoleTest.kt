package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.core.proto.app.v1.GroupControl
import ir.vmessenger.core.proto.app.v1.GroupMember
import kotlin.test.Test
import kotlin.test.assertEquals
import ir.vmessenger.core.proto.app.v1.GroupMemberRole as ProtoGroupMemberRole

/**
 * How a snapshot's claimed roles are read.
 *
 * This matters because an ADMIN is the role that may review other members' edited and deleted
 * messages. Every case here is a way someone could try to become one without the creator saying so.
 */
class GroupControlCodecRoleTest {
    @Test
    fun `an admin the creator named is recorded as an admin`() {
        val members = decode(
            member(MEMBER_HASH, ProtoGroupMemberRole.GROUP_MEMBER_ROLE_ADMIN),
            member(CREATOR_HASH, ProtoGroupMemberRole.GROUP_MEMBER_ROLE_CREATOR),
        )

        assertEquals(GroupMemberRole.ADMIN, members.single { it.identityHash == MEMBER_HASH }.role)
        assertEquals(GroupMemberRole.CREATOR, members.single { it.identityHash == CREATOR_HASH }.role)
    }

    @Test
    fun `a snapshot with no roles at all produces plain members`() {
        // This is a 1.1.2 peer's snapshot: the field does not exist, so it arrives unspecified. The
        // group must come out as ordinary members, never as a group where everyone can audit.
        val members = decode(
            member(MEMBER_HASH, ProtoGroupMemberRole.GROUP_MEMBER_ROLE_UNSPECIFIED),
            member(OTHER_HASH, ProtoGroupMemberRole.GROUP_MEMBER_ROLE_UNSPECIFIED),
        )

        assertEquals(listOf(GroupMemberRole.MEMBER, GroupMemberRole.MEMBER), members.map { it.role })
    }

    @Test
    fun `a member claiming to be the creator is still only a member`() {
        // The creator is decided by which hash matches `creatorHash`, not by what the row says, so
        // re-labelling yourself in a forwarded snapshot gains nothing.
        val members = decode(member(MEMBER_HASH, ProtoGroupMemberRole.GROUP_MEMBER_ROLE_CREATOR))

        assertEquals(GroupMemberRole.MEMBER, members.single().role)
    }

    @Test
    fun `the creator is the creator even in a snapshot that demotes them`() {
        val members = decode(member(CREATOR_HASH, ProtoGroupMemberRole.GROUP_MEMBER_ROLE_MEMBER))

        assertEquals(GroupMemberRole.CREATOR, members.single().role)
    }

    private fun decode(vararg members: GroupMember): List<ir.vmessenger.core.database.entity.GroupMemberEntity> =
        GroupControlCodec.members(
            control = GroupControl.newBuilder().addAllMembers(members.toList()).build(),
            groupId = GROUP_ID,
            creatorHash = CREATOR_HASH,
            now = 1L,
        )

    private fun member(hash: String, role: ProtoGroupMemberRole): GroupMember = GroupMember.newBuilder()
        .setIdentityHash(ByteString.copyFromUtf8(hash))
        .setIdentityPub(ByteString.copyFrom(ByteArray(KEY_SIZE) { 1 }))
        .setDisplayName("someone")
        .setRole(role)
        .build()

    private companion object {
        const val KEY_SIZE = 32
        const val GROUP_ID = "0102030405060708090a0b0c0d0e0f10"
        const val CREATOR_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val MEMBER_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OTHER_HASH = "cccccccccccccccccccccccccccccccc"
    }
}
