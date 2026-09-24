package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.group.GroupSyncTracker
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.core.proto.app.v1.GroupControlType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.repository.GroupFixtures
import ir.vmessenger.network.messaging.IncomingEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A contact still on 1.1.2, as 2.0 receives them.
 *
 * What 1.1.2 sends has none of 2.0's fields: no deadline on a message, no roles in a group, no audit
 * retention. Each has to read as the 1.1.2 behaviour — kept for ever, plain members, nothing kept —
 * and never as a zero that means something else. The bytes themselves are held by core:proto's
 * V1WireRoundTripTest; this is what 2.0 then does with them.
 */
class V1PeerCompatibilityTest {
    private val creator = InboundFixtures.peer(CREATOR_SEED)
    private lateinit var harness: InboundHarness

    @Before
    fun setUp() {
        GroupSyncTracker.clear()
        harness = InboundHarness(selfSeed = SELF_SEED)
        harness.contactDao.contacts += InboundFixtures.contact(CONTACT, creator)
    }

    @After
    fun tearDown() = GroupSyncTracker.clear()

    @Test
    fun `a message from 1_1_2 is kept, and no expiry sweep ever takes it`() = runTest {
        deliver(InboundFixtures.chatEnvelope("m1", "salam"))

        val stored = harness.messageDao.getById("m1")
        assertNotNull(stored)
        assertNull("no deadline, rather than a deadline at epoch zero", stored!!.expiresAtUnixMs)

        harness.writer.purgeExpired(now = Long.MAX_VALUE)

        assertNotNull(harness.messageDao.getById("m1"))
    }

    @Test
    fun `a group created on 1_1_2 arrives with plain members and nothing kept for review`() = runTest {
        val members = listOf(
            GroupFixtures.member(seed = CREATOR_SEED),
            GroupFixtures.member(seed = MEMBER_SEED),
            GroupFixtures.member(seed = SELF_SEED),
        )
        val created = GroupControlCodec.envelope(
            selfIdentityHash = creator.identityHash,
            group = GroupFixtures.group(creatorKey = IdentityHashMatcher.routingKeyHex(creator.identityHash)),
            type = GroupControlType.GROUP_CONTROL_TYPE_CREATE,
            members = members,
            version = 1L,
            targetIdentityHash = null,
        ).asSentBy112()

        harness.groupControlHandler.handle(CONTACT, created)

        val group = harness.groupDao.getById(GroupFixtures.GROUP_ID)
        assertNotNull(group)
        assertFalse(group!!.auditRetention)
        val roles = harness.groupDao.activeMembers(GroupFixtures.GROUP_ID).associate { it.identityHash to it.role }
        assertEquals(GroupMemberRole.CREATOR, roles[GroupFixtures.routingKey(CREATOR_SEED)])
        assertEquals(GroupMemberRole.MEMBER, roles[GroupFixtures.routingKey(MEMBER_SEED)])
        assertEquals(GroupMemberRole.MEMBER, roles[GroupFixtures.routingKey(SELF_SEED)])
    }

    private suspend fun deliver(envelope: MessageEnvelope) {
        harness.collector.handleIncoming(IncomingEnvelope(envelope = envelope, contactId = CONTACT, session = null))
    }

    /** Strips what 1.1.2 does not have, so the control is byte for byte what a 1.1.2 creator sends. */
    private fun MessageEnvelope.asSentBy112(): MessageEnvelope {
        val members = groupControl.membersList.map { it.toBuilder().clearRole().build() }
        val control = groupControl.toBuilder()
            .clearAuditRetention()
            .clearTargetRole()
            .clearMembers()
            .addAllMembers(members)
        return toBuilder().setGroupControl(control).build()
    }

    private companion object {
        const val CONTACT = "a"
        const val SELF_SEED: Byte = 0x01
        const val CREATOR_SEED: Byte = 0x0A
        const val MEMBER_SEED: Byte = 0x0B
    }
}
