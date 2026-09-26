package ir.vmessenger.data.repository

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.database.dao.GroupDao
import ir.vmessenger.core.database.dao.IdentityDao
import ir.vmessenger.core.database.dao.MessageEditHistoryDao
import ir.vmessenger.core.database.dao.MessageRecipientDao
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.database.entity.GroupMemberEntity
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.core.database.entity.IdentityEntity
import ir.vmessenger.core.database.entity.MessageEditHistoryEntity
import ir.vmessenger.core.database.entity.MessageRecipientEntity
import ir.vmessenger.data.network.rank
import ir.vmessenger.domain.model.Identity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory `chat_group` + `chat_group_member`.
 *
 * [groups] and [members] are exposed as the same list instances the conversation
 * and message fakes join over, so a group chat list row is assembled from one set
 * of rows exactly as the real query does.
 */
@Suppress("TooManyFunctions") // mirrors the full GroupDao contract
class FakeGroupDao(
    val groups: MutableList<GroupEntity> = mutableListOf(),
    val members: MutableList<GroupMemberEntity> = mutableListOf(),
) : GroupDao {
    /** IGNORE, like the real DAO: replacing the row would cascade the group's history away. */
    override suspend fun insert(group: GroupEntity) {
        if (groups.none { it.id == group.id }) groups += group
    }

    override suspend fun update(group: GroupEntity) {
        groups.replaceAll { if (it.id == group.id) group else it }
    }

    override suspend fun getById(groupId: String): GroupEntity? = groups.firstOrNull { it.id == groupId }

    override fun observe(groupId: String): Flow<GroupEntity?> = flowOf(groups.firstOrNull { it.id == groupId })

    override suspend fun setName(groupId: String, name: String, version: Long) {
        groups.replaceAll { if (it.id == groupId) it.copy(name = name, version = version) else it }
    }

    override suspend fun setVersion(groupId: String, version: Long) {
        groups.replaceAll { if (it.id == groupId) it.copy(version = version) else it }
    }

    override suspend fun setClosed(groupId: String, closed: Boolean) {
        groups.replaceAll { if (it.id == groupId) it.copy(closed = closed) else it }
    }

    override suspend fun setAuditRetention(groupId: String, enabled: Boolean, version: Long) {
        groups.replaceAll {
            if (it.id == groupId) it.copy(auditRetention = enabled, version = version) else it
        }
    }

    override suspend fun setMemberRole(groupId: String, identityHash: String, role: GroupMemberRole) {
        members.replaceAll {
            if (it.groupId == groupId && it.identityHash == identityHash) it.copy(role = role) else it
        }
    }

    /** INSERT OR REPLACE, so a member in a fresh snapshot loses their tombstone. */
    override suspend fun upsertMembers(members: List<GroupMemberEntity>) {
        for (member in members) {
            this.members.removeAll { it.groupId == member.groupId && it.identityHash == member.identityHash }
            this.members += member
        }
    }

    override fun observeActiveMembers(groupId: String): Flow<List<GroupMemberEntity>> =
        flowOf(activeOf(groupId).sortedWith(compareBy({ it.role }, { it.displayName.lowercase() })))

    override suspend fun activeMembers(groupId: String): List<GroupMemberEntity> = activeOf(groupId)

    override suspend fun member(groupId: String, identityHash: String): GroupMemberEntity? =
        members.firstOrNull { it.groupId == groupId && it.identityHash == identityHash }

    override suspend fun markRemoved(groupId: String, identityHash: String, atUnixMs: Long) {
        members.replaceAll {
            if (it.groupId == groupId && it.identityHash == identityHash && it.removedAtUnixMs == null) {
                it.copy(removedAtUnixMs = atUnixMs)
            } else {
                it
            }
        }
    }

    override suspend fun markAllRemoved(groupId: String, atUnixMs: Long) {
        members.replaceAll { if (it.groupId == groupId) it.copy(removedAtUnixMs = atUnixMs) else it }
    }

    override suspend fun deleteById(groupId: String) {
        groups.removeAll { it.id == groupId }
        members.removeAll { it.groupId == groupId }
    }

    private fun activeOf(groupId: String): List<GroupMemberEntity> =
        members.filter { it.groupId == groupId && it.removedAtUnixMs == null }
}

/** In-memory `message_recipient`, keyed by the `(messageId, identityHash)` pair. */
class FakeMessageRecipientDao : MessageRecipientDao {
    val rows = mutableListOf<MessageRecipientEntity>()

    override suspend fun insertAll(rows: List<MessageRecipientEntity>) {
        for (row in rows) {
            this.rows.removeAll { it.messageId == row.messageId && it.identityHash == row.identityHash }
            this.rows += row
        }
    }

    override suspend fun forMessage(messageId: String): List<MessageRecipientEntity> =
        rows.filter { it.messageId == messageId }

    /** The WHERE clause of the real UPDATE: the row only moves when [rank] beats its current rank. */
    @Suppress("LongParameterList") // one parameter per timestamp column, like the DAO it stands in for
    override suspend fun advance(
        messageId: String,
        identityHash: String,
        status: DeliveryStatus,
        rank: Int,
        sentAt: Long?,
        deliveredAt: Long?,
        readAt: Long?,
    ) {
        rows.replaceAll { row ->
            if (row.messageId == messageId && row.identityHash == identityHash && row.status.rank() < rank) {
                row.copy(
                    status = status,
                    sentAtUnixMs = sentAt ?: row.sentAtUnixMs,
                    deliveredAtUnixMs = deliveredAt ?: row.deliveredAtUnixMs,
                    readAtUnixMs = readAt ?: row.readAtUnixMs,
                )
            } else {
                row
            }
        }
    }

    override suspend fun markFailed(messageId: String, identityHash: String) {
        rows.replaceAll {
            if (it.messageId == messageId && it.identityHash == identityHash) {
                it.copy(status = DeliveryStatus.FAILED)
            } else {
                it
            }
        }
    }

    override suspend fun deleteForMessage(messageId: String) {
        rows.removeAll { it.messageId == messageId }
    }
}

/** The single-row `identity` table, as the recipient resolver reads it to find "us". */
class FakeIdentityDao : IdentityDao {
    var identity: IdentityEntity? = null

    override fun observeIdentity(): Flow<IdentityEntity?> = flowOf(identity)

    override suspend fun getIdentity(): IdentityEntity? = identity

    override suspend fun insertIdentity(entity: IdentityEntity) {
        identity = entity
    }

    override suspend fun deleteAll() {
        identity = null
    }
}

/** Deterministic group rows: ids and identity hashes are derived from a seed byte. */
object GroupFixtures {
    /** A 16-byte routing key, the form every recipient is identified by. */
    fun routingKey(seed: Byte): String = IdentityHashMatcher.routingKeyHex(identityHash(seed))

    fun identityHash(seed: Byte): ByteArray = UserHashEncoder.identityHashFromPublicKey(ByteArray(32) { seed })

    fun group(
        id: String = GROUP_ID,
        creatorKey: String,
        version: Long = 1L,
        closed: Boolean = false,
        name: String = "Team",
    ) = GroupEntity(
        id = id,
        name = name,
        creatorIdentityHash = creatorKey,
        createdAtUnixMs = 0L,
        version = version,
        closed = closed,
        avatarSeed = id,
    )

    fun member(
        groupId: String = GROUP_ID,
        seed: Byte,
        role: GroupMemberRole = GroupMemberRole.MEMBER,
        removedAtUnixMs: Long? = null,
        displayName: String = "Member $seed",
    ) = GroupMemberEntity(
        groupId = groupId,
        identityHash = routingKey(seed),
        identityPub = ByteArray(32) { seed },
        x25519StaticPub = ByteArray(32) { (seed + 1).toByte() },
        displayName = displayName,
        role = role,
        joinedAtUnixMs = 0L,
        removedAtUnixMs = removedAtUnixMs,
    )

    fun identityRow(seed: Byte) = identityRow(
        Identity(
            ed25519PublicKey = ByteArray(32) { seed },
            identityHash = identityHash(seed),
            userHash = UserHashEncoder.encode(identityHash(seed)),
            displayName = "Me",
            x25519StaticPublicKey = ByteArray(32) { (seed + 1).toByte() },
            createdAtUnixMs = 0L,
        ),
    )

    /** The `identity` row matching a domain identity, so both views of "us" agree. */
    fun identityRow(identity: Identity) = IdentityEntity(
        ed25519Public = identity.ed25519PublicKey,
        identityHash = identity.identityHash,
        userHash = identity.userHash,
        displayName = identity.displayName,
        x25519StaticPublic = identity.x25519StaticPublicKey,
        createdAtUnixMs = identity.createdAtUnixMs,
    )

    /** 32 hex chars, the shape `GroupControlCodec.groupIdOf` accepts on the wire. */
    const val GROUP_ID = "0123456789abcdef0123456789abcdef"
}

/**
 * In-memory audit captures, so the retention policy can be asserted without a database. [referenced]
 * answers "does a message row still point at this file?" for [orphanedAttachmentPaths], [reviewed]
 * "is this group held with review on?" for [groupsHeldWithoutReview].
 */
class FakeMessageEditHistoryDao(
    private val referenced: (String) -> Boolean = { false },
    private val reviewed: (String) -> Boolean = { false },
) : MessageEditHistoryDao {
    val rows = mutableListOf<MessageEditHistoryEntity>()

    override suspend fun insert(entity: MessageEditHistoryEntity) {
        rows += entity
    }

    override suspend fun forGroup(groupId: String, limit: Int): List<MessageEditHistoryEntity> =
        rows.filter { it.groupId == groupId }.sortedByDescending { it.capturedAtUnixMs }.take(limit)

    override fun observeForGroup(groupId: String, limit: Int): Flow<List<MessageEditHistoryEntity>> =
        flowOf(rows.filter { it.groupId == groupId }.sortedByDescending { it.capturedAtUnixMs }.take(limit))

    override suspend fun forMessage(messageId: String): List<MessageEditHistoryEntity> =
        rows.filter { it.messageId == messageId }.sortedBy { it.capturedAtUnixMs }

    override suspend fun countForGroup(groupId: String): Int = rows.count { it.groupId == groupId }

    override suspend fun orphanedAttachmentPaths(groupId: String): List<String> =
        rows.filter { it.groupId == groupId }.mapNotNull { it.attachmentPath }.distinct().filterNot(referenced)

    override suspend fun groupsHeldWithoutReview(): List<String> =
        rows.map { it.groupId }.distinct().filterNot(reviewed)

    override suspend fun deleteForGroup(groupId: String) {
        rows.removeAll { it.groupId == groupId }
    }

    override suspend fun purgeOlderThan(cutoffUnixMs: Long) {
        rows.removeAll { it.capturedAtUnixMs < cutoffUnixMs }
    }
}
