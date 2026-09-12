package ir.vmessenger.data.repository

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.data.network.InboundFixtures
import ir.vmessenger.data.network.InboundHarness
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Who a message has to reach. Recipients are routing keys (the first 16 bytes of
 * the identity hash as hex) because that prefix is the only thing a group member
 * row and a contact row are guaranteed to share.
 */
class MessageRecipientResolverTest {
    private val peerA = InboundFixtures.peer(0x0A)
    private val peerB = InboundFixtures.peer(0x0B)
    private val peerD = InboundFixtures.peer(DEPARTED_SEED)

    private lateinit var harness: InboundHarness
    private lateinit var resolver: MessageRecipientResolver

    @Before
    fun setUp() {
        harness = InboundHarness(selfSeed = SELF_SEED)
        resolver = harness.recipientResolver
    }

    @Test
    fun `a one to one chat resolves to its single contact`() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        seedDirectConversation()

        assertEquals(listOf(key(peerA)), resolver.resolve(DIRECT_ID))
    }

    @Test
    fun `a blocked contact resolves to nothing`() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA, blocked = true)
        seedDirectConversation()

        assertTrue(resolver.resolve(DIRECT_ID).isEmpty())
    }

    @Test
    fun `a contact that is not approved resolves to nothing`() = runTest {
        harness.contactDao.contacts +=
            InboundFixtures.contact("a", peerA, status = ContactRelationshipStatus.PENDING_OUT)
        seedDirectConversation()

        assertTrue(resolver.resolve(DIRECT_ID).isEmpty())
    }

    @Test
    fun `an unknown conversation resolves to nothing`() = runTest {
        assertTrue(resolver.resolve("nowhere").isEmpty())
    }

    @Test
    fun `a group resolves to active members we hold an approved contact for`() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB)
        seedGroup()

        val recipients = resolver.resolve(GROUP_CONVERSATION_ID)

        // C is a member we have no contact for; D left; and we are never our own recipient.
        assertEquals(setOf(key(peerA), key(peerB)), recipients.toSet())
    }

    @Test
    fun `a group member whose contact is blocked is skipped`() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB, blocked = true)
        seedGroup()

        assertEquals(listOf(key(peerA)), resolver.resolve(GROUP_CONVERSATION_ID))
    }

    @Test
    fun `our own routing key is the identity row's prefix`() = runTest {
        assertEquals(GroupFixtures.routingKey(SELF_SEED), resolver.selfRoutingKey())
    }

    private fun seedDirectConversation() {
        harness.conversationDao.conversations += ConversationEntity(
            id = DIRECT_ID,
            contactId = "a",
            lastMessageId = null,
            lastActivityUnixMs = 0L,
            unreadCount = 0,
            muted = false,
        )
    }

    /** Creator A, member B, member C (no contact of ours), departed member D, and us. */
    private suspend fun seedGroup() {
        harness.groupDao.insert(GroupFixtures.group(creatorKey = key(peerA)))
        harness.groupDao.upsertMembers(
            listOf(
                GroupFixtures.member(seed = 0x0A, role = GroupMemberRole.CREATOR),
                GroupFixtures.member(seed = 0x0B),
                GroupFixtures.member(seed = 0x0C),
                GroupFixtures.member(seed = DEPARTED_SEED, removedAtUnixMs = 10L),
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
        // The departed member is a perfectly good contact; membership is what excludes them.
        harness.contactDao.contacts += InboundFixtures.contact("d", peerD)
    }

    private fun key(peer: PeerIdentity): String = IdentityHashMatcher.routingKeyHex(peer.identityHash)

    private companion object {
        const val DIRECT_ID = "c1"
        const val GROUP_CONVERSATION_ID = "g1"
        const val SELF_SEED: Byte = 0x01
        const val DEPARTED_SEED: Byte = 0x0D
    }
}
