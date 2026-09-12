package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.GroupDao
import ir.vmessenger.core.database.dao.IdentityDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.database.entity.GroupMemberEntity
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.core.proto.app.v1.GroupControlType
import ir.vmessenger.data.network.GroupControlCodec
import ir.vmessenger.data.network.GroupControlFanOut
import ir.vmessenger.data.network.GroupControlFanOutRequest
import ir.vmessenger.data.network.MAX_GROUP_MEMBERS
import ir.vmessenger.domain.model.Group
import ir.vmessenger.domain.model.GroupMember
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.GroupRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Groups, as this device sees them.
 *
 * Every mutation is creator-only except [leaveGroup], and the check is made
 * against our own identity rather than against what the caller claims: the UI
 * hides the actions, but a bug there must not be able to emit a control nobody
 * would accept anyway.
 *
 * Each accepted change bumps the group's version by exactly one and fans the
 * control out to every member, which is what lets a device that missed one
 * notice the gap and ask for a snapshot instead of diverging in silence.
 */
@Singleton
@Suppress("TooManyFunctions", "LongParameterList") // one method per membership operation; one DAO per table
class GroupRepositoryImpl @Inject constructor(
    private val groupDao: GroupDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val identityDao: IdentityDao,
    private val contactRepository: ContactRepository,
    private val fanOut: GroupControlFanOut,
    private val cryptoEngine: CryptoEngine,
) : GroupRepository {

    override fun observeGroup(groupId: String): Flow<Group?> =
        flow { emit(selfKey()) }.flatMapLatest { selfKey ->
            groupDao.observe(groupId).map { it?.toDomain(selfKey) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMembers(groupId: String): Flow<List<GroupMember>> =
        flow { emit(selfKey()) }.flatMapLatest { selfKey ->
            groupDao.observeActiveMembers(groupId).map { members ->
                members.map { it.toDomain(selfKey, contactDao.getByRoutingKey(it.identityHash)) }
            }
        }

    override suspend fun getGroup(groupId: String): Group? = groupDao.getById(groupId)?.toDomain(selfKey())

    override suspend fun createGroup(name: String, memberContactIds: List<String>): AppResult<String> {
        val self = selfMember()
        val others = memberContactIds.mapNotNull { contactDao.getById(it) }
        return when {
            self == null -> AppResult.Error(AppError.NotFound("identity"))
            // The user counts towards the cap, so 31 others is the most that fits.
            others.size + 1 > MAX_GROUP_MEMBERS -> AppResult.Error(AppError.GroupFull(MAX_GROUP_MEMBERS))
            else -> AppResult.Success(create(name, self, others))
        }
    }

    /** Writes the group, its membership and its conversation, then announces it. */
    private suspend fun create(
        name: String,
        self: GroupMemberEntity,
        others: List<ContactEntity>,
    ): String {
        val now = System.currentTimeMillis()
        val groupId = GroupControlCodec.newGroupId(cryptoEngine.randomBytes(GROUP_ID_BYTES))
        val group = GroupEntity(
            id = groupId,
            name = name.trim().take(GroupControlCodec.MAX_NAME_LENGTH),
            creatorIdentityHash = self.identityHash,
            createdAtUnixMs = now,
            version = 1,
            closed = false,
            avatarSeed = groupId,
        )
        val members = listOf(self.copy(groupId = groupId, joinedAtUnixMs = now)) +
            others.map { it.toMember(groupId, now) }
        groupDao.insert(group)
        groupDao.upsertMembers(members)
        val conversationId = createConversation(groupId, now)
        fanOut.send(
            GroupControlFanOutRequest(
                group = group,
                conversationId = conversationId,
                type = GroupControlType.GROUP_CONTROL_TYPE_CREATE,
                members = members,
                version = 1,
                systemText = GroupEventText.created(group.name),
            ),
        )
        AppLogger.info(TAG, "group created id=$groupId members=${members.size}")
        return conversationId
    }

    override suspend fun renameGroup(groupId: String, name: String): AppResult<Unit> =
        asCreator(groupId) { group, conversationId ->
            val trimmed = name.trim().take(GroupControlCodec.MAX_NAME_LENGTH)
            val version = group.version + 1
            groupDao.setName(groupId, trimmed, version)
            fanOut.send(
                GroupControlFanOutRequest(
                    group = group.copy(name = trimmed, version = version),
                    conversationId = conversationId,
                    type = GroupControlType.GROUP_CONTROL_TYPE_UPDATE_NAME,
                    members = groupDao.activeMembers(groupId),
                    version = version,
                    systemText = GroupEventText.renamed(trimmed),
                ),
            )
            AppResult.Success(Unit)
        }

    /**
     * One control per addition: each names its target, so every device can render
     * "X was added" instead of diffing two member lists — and each carries the
     * full membership, so a device that missed an earlier one still converges.
     */
    override suspend fun addMembers(groupId: String, memberContactIds: List<String>): AppResult<Unit> =
        asCreator(groupId) { group, conversationId ->
            val current = groupDao.activeMembers(groupId)
            val known = current.map { it.identityHash }.toSet()
            val now = System.currentTimeMillis()
            val added = memberContactIds.mapNotNull { contactDao.getById(it) }
                .map { it.toMember(groupId, now) }
                .filterNot { it.identityHash in known }
            when {
                added.isEmpty() -> AppResult.Success(Unit)
                current.size + added.size > MAX_GROUP_MEMBERS -> AppResult.Error(AppError.GroupFull(MAX_GROUP_MEMBERS))
                else -> {
                    groupDao.upsertMembers(added)
                    announceAdditions(group, conversationId, current + added, added)
                    AppResult.Success(Unit)
                }
            }
        }

    private suspend fun announceAdditions(
        group: GroupEntity,
        conversationId: String,
        members: List<GroupMemberEntity>,
        added: List<GroupMemberEntity>,
    ) {
        var version = group.version
        for (member in added) {
            version += 1
            groupDao.setVersion(group.id, version)
            fanOut.send(
                GroupControlFanOutRequest(
                    group = group.copy(version = version),
                    conversationId = conversationId,
                    type = GroupControlType.GROUP_CONTROL_TYPE_ADD,
                    members = members,
                    version = version,
                    target = member.identityHash,
                    systemText = GroupEventText.added(member.displayName),
                ),
            )
        }
    }

    override suspend fun removeMember(groupId: String, identityHash: String): AppResult<Unit> =
        asCreator(groupId) { group, conversationId ->
            val member = groupDao.member(groupId, identityHash)
            if (member == null || member.removedAtUnixMs != null) {
                AppResult.Error(AppError.NotFound("member $identityHash"))
            } else {
                val version = group.version + 1
                groupDao.markRemoved(groupId, identityHash, System.currentTimeMillis())
                groupDao.setVersion(groupId, version)
                fanOut.send(
                    GroupControlFanOutRequest(
                        group = group.copy(version = version),
                        conversationId = conversationId,
                        type = GroupControlType.GROUP_CONTROL_TYPE_REMOVE,
                        members = groupDao.activeMembers(groupId),
                        version = version,
                        target = identityHash,
                        systemText = GroupEventText.removed(member.displayName),
                        extraRecipient = identityHash,
                    ),
                )
                AppResult.Success(Unit)
            }
        }

    /**
     * The creator cannot leave — nobody would be left to arbitrate the membership —
     * so for them this closes the group instead.
     */
    override suspend fun leaveGroup(groupId: String): AppResult<Unit> {
        val group = groupDao.getById(groupId)
        val self = selfKey()
        val conversationId = conversationDao.getByGroupId(groupId)?.id
        return when {
            group == null || self == null || conversationId == null ->
                AppResult.Error(AppError.NotFound("group $groupId"))
            group.creatorIdentityHash == self -> closeGroup(groupId)
            else -> leave(group, self, conversationId)
        }
    }

    private suspend fun leave(group: GroupEntity, self: String, conversationId: String): AppResult<Unit> {
        // Announced before we drop ourselves from the membership, so the fan-out
        // still knows who to tell.
        fanOut.send(
            GroupControlFanOutRequest(
                group = group,
                conversationId = conversationId,
                type = GroupControlType.GROUP_CONTROL_TYPE_LEAVE,
                members = emptyList(),
                version = group.version,
                target = self,
                systemText = GroupEventText.LEFT_BY_ME,
            ),
        )
        groupDao.markRemoved(group.id, self, System.currentTimeMillis())
        groupDao.setClosed(group.id, true)
        return AppResult.Success(Unit)
    }

    override suspend fun closeGroup(groupId: String): AppResult<Unit> =
        asCreator(groupId) { group, conversationId ->
            val version = group.version + 1
            groupDao.setVersion(groupId, version)
            groupDao.setClosed(groupId, true)
            fanOut.send(
                GroupControlFanOutRequest(
                    group = group.copy(version = version),
                    conversationId = conversationId,
                    type = GroupControlType.GROUP_CONTROL_TYPE_CLOSE,
                    members = groupDao.activeMembers(groupId),
                    version = version,
                    systemText = GroupEventText.closed(group.name),
                ),
            )
            AppResult.Success(Unit)
        }

    override suspend fun addMemberAsContact(groupId: String, identityHash: String): AppResult<Unit> {
        val member = groupDao.member(groupId, identityHash)
            ?: return AppResult.Error(AppError.NotFound("member $identityHash"))
        val userHash = UserHashEncoder.encode(UserHashEncoder.identityHashFromPublicKey(member.identityPub))
        return when (val result = contactRepository.addContactByUserHash(userHash, member.displayName)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Error -> result
        }
    }

    /**
     * Runs [block] only when we created [groupId] and it is still open. Closed is
     * refused as well as non-creator: a closed group is history, not a thing to
     * keep changing.
     */
    private suspend fun asCreator(
        groupId: String,
        block: suspend (GroupEntity, String) -> AppResult<Unit>,
    ): AppResult<Unit> {
        val group = groupDao.getById(groupId)
        val conversationId = conversationDao.getByGroupId(groupId)?.id
        return when {
            group == null || conversationId == null -> AppResult.Error(AppError.NotFound("group $groupId"))
            group.creatorIdentityHash != selfKey() -> AppResult.Error(AppError.NotGroupCreator)
            group.closed -> AppResult.Error(AppError.GroupClosed)
            else -> block(group, conversationId)
        }
    }

    private suspend fun createConversation(groupId: String, now: Long): String {
        val id = UUID.randomUUID().toString()
        conversationDao.upsert(
            ConversationEntity(
                id = id,
                contactId = null,
                groupId = groupId,
                lastMessageId = null,
                lastActivityUnixMs = now,
                unreadCount = 0,
                muted = false,
            ),
        )
        return id
    }

    private suspend fun selfKey(): String? =
        identityDao.getIdentity()?.identityHash?.let(IdentityHashMatcher::routingKeyHex)

    /** Our own membership row, built from the identity we publish to peers. */
    private suspend fun selfMember(): GroupMemberEntity? {
        val identity = identityDao.getIdentity() ?: return null
        return GroupMemberEntity(
            groupId = "",
            identityHash = IdentityHashMatcher.routingKeyHex(identity.identityHash),
            identityPub = identity.ed25519Public,
            x25519StaticPub = identity.x25519StaticPublic,
            displayName = identity.displayName,
            role = GroupMemberRole.CREATOR,
            joinedAtUnixMs = 0,
            removedAtUnixMs = null,
        )
    }

    private companion object {
        const val TAG = "Groups"
        const val GROUP_ID_BYTES = 16
    }
}
