package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.group.GroupSyncTracker
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.database.entity.GroupMemberEntity
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEditHistoryEntity
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.MessageRevisionKind
import ir.vmessenger.core.proto.app.v1.GroupControlType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.repository.GroupEventText
import ir.vmessenger.data.repository.GroupFixtures
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Membership changes from a peer. With no server to arbitrate, authority is the
 * group's creator plus a version that only moves forward by one — and the
 * "sender" is the contact the *session* authenticated, never the envelope's own
 * sender field.
 *
 * The handler's [GroupControlSender] cannot be observed here (it depends on the
 * concrete `MessagingService`), so the snapshot-request side of an out-of-order
 * control is asserted as "nothing was applied" only.
 */
@Suppress("TooManyFunctions") // one test per control type, plus the fixtures they share
class GroupControlHandlerTest {
    private val peerA = InboundFixtures.peer(CREATOR_SEED)
    private val peerB = InboundFixtures.peer(MEMBER_SEED)

    private lateinit var harness: InboundHarness
    private lateinit var handler: GroupControlHandler

    @Before
    fun setUp() {
        GroupSyncTracker.clear()
        harness = InboundHarness(selfSeed = SELF_SEED)
        handler = harness.groupControlHandler
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB)
    }

    @After
    fun tearDown() = GroupSyncTracker.clear()

    @Test
    fun `a create from the creator that includes us is stored`() = runTest {
        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_CREATE, version = 1L))

        val group = harness.groupDao.getById(GroupFixtures.GROUP_ID)
        assertNotNull(group)
        assertEquals(1L, group!!.version)
        assertEquals(key(peerA), group.creatorIdentityHash)
        assertEquals(3, harness.groupDao.activeMembers(GroupFixtures.GROUP_ID).size)
        val conversation = harness.conversationDao.getByGroupId(GroupFixtures.GROUP_ID)
        assertNotNull(conversation)
        assertEquals(GroupEventText.created("Team"), harness.messageDao.messages.single().body)
    }

    @Test
    fun `a create from someone who is not the named creator is dropped`() = runTest {
        // B relays a control that names A as the creator; the session says it is B.
        handler.handle("b", control(GroupControlType.GROUP_CONTROL_TYPE_CREATE, version = 1L))

        assertNull(harness.groupDao.getById(GroupFixtures.GROUP_ID))
        assertTrue(harness.conversationDao.conversations.isEmpty())
        assertTrue(harness.messageDao.messages.isEmpty())
    }

    @Test
    fun `a snapshot that does not include us is dropped`() = runTest {
        val withoutUs = listOf(
            GroupFixtures.member(seed = CREATOR_SEED, role = GroupMemberRole.CREATOR),
            GroupFixtures.member(seed = MEMBER_SEED),
        )

        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT, version = 1L, members = withoutUs))

        assertNull(harness.groupDao.getById(GroupFixtures.GROUP_ID))
    }

    @Test
    fun `a snapshot older than what we hold is ignored`() = runTest {
        seedLocalGroup(version = 5L)

        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT, version = 4L, name = "Renamed"))

        val group = harness.groupDao.getById(GroupFixtures.GROUP_ID)
        assertEquals(5L, group?.version)
        assertEquals("Team", group?.name)
    }

    @Test
    fun `an incremental control out of sequence is dropped`() = runTest {
        seedLocalGroup(version = 1L)

        // A gap: v3 while we hold v1. This is where a snapshot is requested.
        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_UPDATE_NAME, version = 3L, name = "Renamed"))

        val group = harness.groupDao.getById(GroupFixtures.GROUP_ID)
        assertEquals(1L, group?.version)
        assertEquals("Team", group?.name)
        assertTrue(harness.messageDao.messages.isEmpty())
    }

    @Test
    fun `a replayed incremental control is ignored`() = runTest {
        seedLocalGroup(version = 2L)

        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_UPDATE_NAME, version = 2L, name = "Renamed"))

        assertEquals("Team", harness.groupDao.getById(GroupFixtures.GROUP_ID)?.name)
    }

    @Test
    fun `an incremental control from a non creator is dropped`() = runTest {
        seedLocalGroup(version = 1L)

        handler.handle("b", control(GroupControlType.GROUP_CONTROL_TYPE_UPDATE_NAME, version = 2L, name = "Renamed"))

        assertEquals("Team", harness.groupDao.getById(GroupFixtures.GROUP_ID)?.name)
        assertEquals(1L, harness.groupDao.getById(GroupFixtures.GROUP_ID)?.version)
    }

    @Test
    fun `an in sequence rename from the creator is applied`() = runTest {
        seedLocalGroup(version = 1L)

        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_UPDATE_NAME, version = 2L, name = "Renamed"))

        assertEquals("Renamed", harness.groupDao.getById(GroupFixtures.GROUP_ID)?.name)
        assertEquals(2L, harness.groupDao.getById(GroupFixtures.GROUP_ID)?.version)
    }

    @Test
    fun `a remove naming us closes the group locally`() = runTest {
        seedLocalGroup(version = 1L)

        handler.handle(
            "a",
            control(GroupControlType.GROUP_CONTROL_TYPE_REMOVE, version = 2L, target = selfKey()),
        )

        val group = harness.groupDao.getById(GroupFixtures.GROUP_ID)
        assertTrue(group!!.closed)
        assertNotNull(harness.groupDao.member(GroupFixtures.GROUP_ID, selfKey())?.removedAtUnixMs)
        assertEquals(GroupEventText.REMOVED_ME, harness.messageDao.messages.single().body)
    }

    @Test
    fun `a remove naming someone else leaves the group open`() = runTest {
        seedLocalGroup(version = 1L)

        handler.handle(
            "a",
            control(GroupControlType.GROUP_CONTROL_TYPE_REMOVE, version = 2L, target = key(peerB)),
        )

        assertFalse(harness.groupDao.getById(GroupFixtures.GROUP_ID)!!.closed)
        assertNotNull(harness.groupDao.member(GroupFixtures.GROUP_ID, key(peerB))?.removedAtUnixMs)
        assertNull(harness.groupDao.member(GroupFixtures.GROUP_ID, selfKey())?.removedAtUnixMs)
    }

    @Test
    fun `a leave only removes the member that sent it`() = runTest {
        seedLocalGroup(version = 1L)

        // B leaves but names A as the target; the target field carries no authority.
        handler.handle(
            "b",
            control(GroupControlType.GROUP_CONTROL_TYPE_LEAVE, version = 2L, target = key(peerA)),
        )

        assertNotNull(harness.groupDao.member(GroupFixtures.GROUP_ID, key(peerB))?.removedAtUnixMs)
        assertNull(harness.groupDao.member(GroupFixtures.GROUP_ID, key(peerA))?.removedAtUnixMs)
        assertEquals(GroupEventText.left("Member 11"), harness.messageDao.messages.single().body)
    }

    @Test
    fun `a control for a group we do not have is dropped`() = runTest {
        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_UPDATE_NAME, version = 2L, name = "Renamed"))

        assertNull(harness.groupDao.getById(GroupFixtures.GROUP_ID))
        assertTrue(harness.messageDao.messages.isEmpty())
    }

    /**
     * The sender re-sends a control until it is acknowledged, so the same snapshot
     * arrives more than once: it must not re-create the conversation or repeat the
     * "group created" line.
     *
     * The version of this bug that actually shipped was worse — the handler upserted
     * the group, and `INSERT OR REPLACE` cascaded the conversation and every message
     * in it away. A list-backed fake cannot model a SQL cascade, so that half is
     * pinned where it is real: `CascadeTest` in `:core:database`.
     */
    @Test
    fun `a re-sent snapshot keeps the group's history`() = runTest {
        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_CREATE, version = 1L))
        val conversationId = harness.conversationDao.getByGroupId(GroupFixtures.GROUP_ID)!!.id
        harness.writer.recordGroupEvent(conversationId, "چیزی که نباید پاک شود")
        val before = harness.messageDao.messages.size

        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_CREATE, version = 1L))

        assertEquals(conversationId, harness.conversationDao.getByGroupId(GroupFixtures.GROUP_ID)?.id)
        assertEquals(before, harness.messageDao.messages.size)
        // And no second "group created" line: the group was already known.
        assertEquals(1, harness.messageDao.messages.count { it.body == GroupEventText.created("Team") })
    }

    /**
     * The gap is real and the creator never answers. Membership is stuck at v1
     * forever, and until this the only sign was a group where nobody's changes
     * ever arrived.
     */
    @Test
    fun `a creator that never answers marks the group out of sync`() = runTest {
        seedLocalGroup(version = 1L)

        repeat(GroupSyncTracker.UNANSWERED_REQUESTS_BEFORE_ALERT) {
            handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_ADD, version = 3L))
        }

        assertTrue(GroupFixtures.GROUP_ID in GroupSyncTracker.outOfSync.value)
    }

    @Test
    fun `a snapshot from the creator clears the out of sync mark`() = runTest {
        seedLocalGroup(version = 1L)
        repeat(GroupSyncTracker.UNANSWERED_REQUESTS_BEFORE_ALERT) {
            handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_ADD, version = 3L))
        }

        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT, version = 3L))

        assertFalse(GroupFixtures.GROUP_ID in GroupSyncTracker.outOfSync.value)
        assertEquals(3L, harness.groupDao.getById(GroupFixtures.GROUP_ID)?.version)
    }

    @Test
    fun `review switched off by the creator erases this member's captures and says so`() = runTest {
        seedLocalGroup(version = 1L, retention = true)
        // One capture kept a deleted message's file; one is an edit that shares the live message's.
        harness.historyDao.rows += capture("m-deleted", attachment = "files/attachments/deleted.vma")
        harness.historyDao.rows += capture("m-edited", attachment = "files/attachments/live.vma")
        harness.messageDao.messages += MessageEntity(
            messageId = "m-edited",
            conversationId = GROUP_CONVERSATION_ID,
            direction = MessageDirection.INCOMING,
            contentType = MessageContentType.IMAGE,
            body = null,
            replyToMessageId = null,
            status = DeliveryStatus.DELIVERED,
            createdAtUnixMs = 1,
            sentAtUnixMs = 1,
            deliveredAtUnixMs = 1,
            readAtUnixMs = null,
            attachmentPath = "files/attachments/live.vma",
            senderIdentityHash = key(peerB),
        )

        handler.handle(
            "a",
            retentionControl(GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT, version = 2L, retention = false),
        )

        assertFalse(harness.groupDao.getById(GroupFixtures.GROUP_ID)!!.auditRetention)
        assertTrue(harness.historyDao.rows.isEmpty())
        assertEquals(listOf("files/attachments/deleted.vma"), harness.attachmentFiles.deleted)
        assertTrue(harness.messageDao.messages.any { it.body == GroupEventText.AUDIT_RETENTION_OFF })
    }

    @Test
    fun `review switched on by the creator is announced to this member`() = runTest {
        seedLocalGroup(version = 1L, retention = false)

        handler.handle(
            "a",
            retentionControl(GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT, version = 2L, retention = true),
        )

        assertTrue(harness.groupDao.getById(GroupFixtures.GROUP_ID)!!.auditRetention)
        assertEquals(GroupEventText.AUDIT_RETENTION_ON, harness.messageDao.messages.single().body)
        assertTrue(harness.attachmentFiles.deleted.isEmpty())
    }

    @Test
    fun `joining a group that already reviews messages tells the new member`() = runTest {
        handler.handle(
            "a",
            retentionControl(GroupControlType.GROUP_CONTROL_TYPE_CREATE, version = 1L, retention = true),
        )

        val lines = harness.messageDao.messages.map { it.body }
        assertEquals(listOf(GroupEventText.created("Team"), GroupEventText.AUDIT_RETENTION_ON), lines)
    }

    @Test
    fun `a snapshot that leaves review as it was writes nothing`() = runTest {
        seedLocalGroup(version = 1L, retention = true)
        harness.historyDao.rows += capture("m1", attachment = null)

        handler.handle(
            "a",
            retentionControl(GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT, version = 2L, retention = true),
        )

        assertEquals(1, harness.historyDao.rows.size)
        assertTrue(harness.messageDao.messages.isEmpty())
    }

    @Test
    fun `a member naming itself creator of a group we hold is dropped and erases nothing`() = runTest {
        seedLocalGroup(version = 1L, retention = true)
        harness.historyDao.rows += capture("m-deleted", attachment = "files/attachments/deleted.vma")
        val forged = GroupControlCodec.envelope(
            selfIdentityHash = peerB.identityHash,
            group = GroupFixtures.group(creatorKey = key(peerB), name = "Team"),
            type = GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT,
            members = members(),
            version = 2L,
        )

        handler.handle("b", forged)

        val group = harness.groupDao.getById(GroupFixtures.GROUP_ID)!!
        assertEquals(key(peerA), group.creatorIdentityHash)
        assertTrue(group.auditRetention)
        assertEquals(1L, group.version)
        assertEquals(1, harness.historyDao.rows.size)
        assertTrue(harness.attachmentFiles.deleted.isEmpty())
        assertTrue(harness.messageDao.messages.isEmpty())
    }

    @Test
    fun `a member added after creation joins from the add that names it`() = runTest {
        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_ADD, version = 3L, target = selfKey()))

        assertEquals(3L, harness.groupDao.getById(GroupFixtures.GROUP_ID)!!.version)
        assertEquals(1, harness.conversationDao.conversations.size)
        assertEquals(GroupEventText.created("Team"), harness.messageDao.messages.single().body)
    }

    @Test
    fun `an add for a group we do not hold is dropped unless the creator names us`() = runTest {
        val other = GroupFixtures.routingKey(MEMBER_SEED)
        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_ADD, version = 3L, target = other))
        handler.handle("b", control(GroupControlType.GROUP_CONTROL_TYPE_ADD, version = 3L, target = selfKey()))

        assertNull(harness.groupDao.getById(GroupFixtures.GROUP_ID))
        assertTrue(harness.messageDao.messages.isEmpty())
    }

    @Test
    fun `captures a member kept under a review since switched off are erased at start`() = runTest {
        seedLocalGroup(version = 2L, retention = false)
        harness.historyDao.rows += capture("m-deleted", attachment = "files/attachments/deleted.vma")
        harness.historyDao.rows += capture("m-other", attachment = null).copy(groupId = "still-reviewed")
        harness.groupDao.insert(
            GroupFixtures.group(id = "still-reviewed", creatorKey = key(peerA)).copy(auditRetention = true),
        )

        handler.eraseReviewLeftovers()

        assertEquals(listOf("still-reviewed"), harness.historyDao.rows.map { it.groupId })
        assertEquals(listOf("files/attachments/deleted.vma"), harness.attachmentFiles.deleted)
    }

    @Test
    fun `being removed erases what this device kept under review`() = runTest {
        seedLocalGroup(version = 1L, retention = true)
        harness.historyDao.rows += capture("m1", attachment = null)

        handler.handle("a", control(GroupControlType.GROUP_CONTROL_TYPE_REMOVE, version = 2L, target = selfKey()))

        assertTrue(harness.historyDao.rows.isEmpty())
    }

    /** A control from the creator carrying the message-review policy [retention]. */
    private fun retentionControl(type: GroupControlType, version: Long, retention: Boolean): MessageEnvelope =
        GroupControlCodec.envelope(
            selfIdentityHash = peerA.identityHash,
            group = claimedGroup("Team").copy(auditRetention = retention),
            type = type,
            members = members(),
            version = version,
        )

    private fun capture(messageId: String, attachment: String?) = MessageEditHistoryEntity(
        messageId = messageId,
        groupId = GroupFixtures.GROUP_ID,
        authorIdentityHash = key(peerB),
        revision = MessageRevisionKind.DELETE,
        body = "before",
        caption = null,
        attachmentName = attachment?.substringAfterLast('/'),
        attachmentPath = attachment,
        capturedAtUnixMs = 1L,
    )

    /** Creator A, member B and us, already stored locally with its conversation. */
    private suspend fun seedLocalGroup(version: Long, retention: Boolean = false) {
        harness.groupDao.insert(
            GroupFixtures.group(creatorKey = key(peerA), version = version).copy(auditRetention = retention),
        )
        harness.groupDao.upsertMembers(members())
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

    private fun members() = listOf(
        GroupFixtures.member(seed = CREATOR_SEED, role = GroupMemberRole.CREATOR),
        GroupFixtures.member(seed = MEMBER_SEED),
        GroupFixtures.member(seed = SELF_SEED),
    )

    /**
     * A control envelope as the creator would build it. [GroupControlCodec] is the
     * real encoder, so these travel exactly like the wire form.
     */
    private fun control(
        type: GroupControlType,
        version: Long,
        name: String = "Team",
        target: String? = null,
        members: List<GroupMemberEntity> = members(),
    ): MessageEnvelope = GroupControlCodec.envelope(
        selfIdentityHash = peerA.identityHash,
        group = claimedGroup(name),
        type = type,
        members = members,
        version = version,
        targetIdentityHash = target,
    )

    /** What the *control* claims the group is; only the creator hash and id bind. */
    private fun claimedGroup(name: String): GroupEntity =
        GroupFixtures.group(creatorKey = key(peerA), name = name)

    private fun selfKey(): String = GroupFixtures.routingKey(SELF_SEED)

    private fun key(peer: PeerIdentity): String = IdentityHashMatcher.routingKeyHex(peer.identityHash)

    private companion object {
        const val GROUP_CONVERSATION_ID = "g1"
        const val SELF_SEED: Byte = 0x01
        const val CREATOR_SEED: Byte = 0x0A
        const val MEMBER_SEED: Byte = 0x0B
    }
}
