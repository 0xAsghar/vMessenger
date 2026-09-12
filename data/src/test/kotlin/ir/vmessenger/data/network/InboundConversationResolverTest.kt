package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.data.repository.GroupFixtures
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Where an inbound envelope is allowed to land. `group_id` is peer-controlled, so
 * being an approved contact is not enough: the group must exist here, be open,
 * and the sender must be one of its active members.
 */
class InboundConversationResolverTest {
    private val peerA = InboundFixtures.peer(0x0A)
    private val peerB = InboundFixtures.peer(0x0B)

    private lateinit var harness: InboundHarness
    private lateinit var resolver: InboundConversationResolver

    @Before
    fun setUp() {
        harness = InboundHarness()
        resolver = harness.conversationResolver
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB)
    }

    @Test
    fun `a one to one message creates the conversation when it does not exist yet`() = runTest {
        val target = resolver.resolve("a", InboundFixtures.chatEnvelope("m1"), NOW)

        assertNotNull(target)
        assertEquals(harness.conversationDao.getByContactId("a")?.id, target!!.conversationId)
        // A 1:1 conversation already says who the sender is.
        assertNull(target.senderIdentityHash)
        assertNull(target.groupName)
    }

    @Test
    fun `a one to one message reuses the existing conversation`() = runTest {
        harness.conversationDao.conversations += ConversationEntity(
            id = "c1",
            contactId = "a",
            lastMessageId = null,
            lastActivityUnixMs = 0L,
            unreadCount = 0,
            muted = false,
        )

        val target = resolver.resolve("a", InboundFixtures.chatEnvelope("m1"), NOW)

        assertEquals("c1", target?.conversationId)
        assertEquals(1, harness.conversationDao.conversations.size)
    }

    @Test
    fun `a group message from an active member resolves to the group conversation`() = runTest {
        seedGroup()

        val target = resolver.resolve("a", groupChat(), NOW)

        assertEquals(GROUP_CONVERSATION_ID, target?.conversationId)
        assertEquals(key(peerA), target?.senderIdentityHash)
        assertEquals("Team", target?.groupName)
    }

    @Test
    fun `a group message from someone who is not a member is dropped`() = runTest {
        seedGroup()

        assertNull(resolver.resolve("b", groupChat(), NOW))
    }

    @Test
    fun `a group message from a removed member is dropped`() = runTest {
        seedGroup()
        harness.groupDao.markRemoved(GroupFixtures.GROUP_ID, key(peerA), atUnixMs = 5L)

        assertNull(resolver.resolve("a", groupChat(), NOW))
    }

    @Test
    fun `a message for a group we do not have is dropped`() = runTest {
        assertNull(resolver.resolve("a", groupChat(), NOW))
        // Nothing is created for a group id the peer made up.
        assertEquals(0, harness.conversationDao.conversations.size)
    }

    @Test
    fun `a message for a closed group is dropped`() = runTest {
        seedGroup()
        harness.groupDao.setClosed(GroupFixtures.GROUP_ID, closed = true)

        assertNull(resolver.resolve("a", groupChat(), NOW))
    }

    /** A group with creator A and us, plus the conversation it is rendered in. */
    private suspend fun seedGroup() {
        harness.groupDao.insert(GroupFixtures.group(creatorKey = key(peerA)))
        harness.groupDao.upsertMembers(
            listOf(
                GroupFixtures.member(seed = 0x0A, role = GroupMemberRole.CREATOR),
                GroupFixtures.member(seed = SELF_SEED),
            ),
        )
        harness.conversationDao.conversations += ConversationEntity(
            id = GROUP_CONVERSATION_ID,
            contactId = null,
            groupId = GroupFixtures.GROUP_ID,
            lastMessageId = null,
            lastActivityUnixMs = 0L,
            unreadCount = 0,
            muted = false,
        )
    }

    private fun groupChat() = InboundFixtures.chatEnvelope("m1", groupId = GroupFixtures.GROUP_ID)

    private fun key(peer: PeerIdentity): String = IdentityHashMatcher.routingKeyHex(peer.identityHash)

    private companion object {
        const val GROUP_CONVERSATION_ID = "g1"
        const val SELF_SEED: Byte = 0x01
        const val NOW = 1_000L
    }
}
