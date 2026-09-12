package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.GroupDao
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.proto.app.v1.GroupControl
import ir.vmessenger.core.proto.app.v1.GroupControlType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.repository.ConversationWriter
import ir.vmessenger.data.repository.GroupEventText
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies membership changes received from a peer.
 *
 * There is no server to arbitrate, so authority is pinned to the group's creator
 * and to a version that only moves forward by one:
 *
 * - `CREATE`/`SNAPSHOT` are accepted only from the creator, and only when they do
 *   not move the group backwards. They replace the membership wholesale, which is
 *   how a device that missed something recovers.
 * - `UPDATE_NAME`/`ADD`/`REMOVE`/`CLOSE` are accepted only from the creator and
 *   only at exactly `local + 1`. A gap means a control was missed: the receiver
 *   asks for a snapshot and drops the control rather than applying a change it
 *   cannot place.
 * - `LEAVE` is the one control a member sends about itself, so it is accepted
 *   from that member only — and never from the creator, who closes instead.
 *
 * Everything else — a control from a non-creator, a control about a group we are
 * not in, a `REMOVE` naming someone who is not a member — is dropped and logged.
 */
@Singleton
@Suppress("TooManyFunctions") // one handler per control type, plus their shared guards
class GroupControlHandler @Inject constructor(
    private val groupDao: GroupDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val writer: ConversationWriter,
    private val controlSender: GroupControlSender,
    private val selfIdentity: SelfIdentityCache,
) {
    /**
     * Handles one inbound control. The sender is taken from [contactId] — the
     * contact the *session* authenticated — and never from the envelope's own
     * `sender_identity_hash`, which is peer-controlled and is not checked
     * anywhere. That is what makes "the sender is the creator" a real check
     * rather than a claim the sender makes about itself.
     */
    suspend fun handle(contactId: String, envelope: MessageEnvelope) {
        val control = envelope.groupControl
        val groupId = GroupControlCodec.groupIdOf(envelope)
        val senderKey = contactDao.getById(contactId)?.identityHash?.let(IdentityHashMatcher::routingKeyHex)
        if (groupId == null || senderKey == null || groupId != control.groupId.toStringUtf8()) {
            AppLogger.warn(TAG, "group control dropped: malformed or unknown sender contact=$contactId")
            return
        }
        val self = selfIdentity.get()?.identityHash?.let(IdentityHashMatcher::routingKeyHex) ?: return
        dispatch(Incoming(groupId, senderKey, self, control))
    }

    private class Incoming(
        val groupId: String,
        val senderKey: String,
        val selfKey: String,
        val control: GroupControl,
    ) {
        val creatorKey: String get() = control.creatorIdentityHash.toStringUtf8()
        val version: Long get() = control.version
    }

    private suspend fun dispatch(incoming: Incoming) {
        val local = groupDao.getById(incoming.groupId)
        when (incoming.control.type) {
            GroupControlType.GROUP_CONTROL_TYPE_CREATE,
            GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT,
            -> applySnapshot(incoming, local)
            GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT_REQUEST -> answerSnapshotRequest(incoming, local)
            GroupControlType.GROUP_CONTROL_TYPE_LEAVE -> applyLeave(incoming, local)
            else -> applyIncremental(incoming, local)
        }
    }

    /**
     * A snapshot replaces everything. It is accepted at `version >= local` rather
     * than `> local` so a re-sent snapshot at the current version still repairs a
     * membership that drifted, while an older one is ignored.
     */
    private suspend fun applySnapshot(incoming: Incoming, local: GroupEntity?) {
        val now = System.currentTimeMillis()
        val members = GroupControlCodec.members(incoming.control, incoming.groupId, incoming.creatorKey, now)
        val rejection = when {
            incoming.senderKey != incoming.creatorKey -> "sender is not the creator"
            local != null && incoming.version < local.version -> "stale version ${incoming.version}"
            members.none { it.identityHash == incoming.selfKey } -> "we are not in it"
            else -> null
        }
        if (rejection != null) {
            AppLogger.warn(TAG, "snapshot dropped: $rejection group=${incoming.groupId}")
            return
        }
        val group = GroupEntity(
            id = incoming.groupId,
            name = incoming.control.name.take(GroupControlCodec.MAX_NAME_LENGTH),
            creatorIdentityHash = incoming.creatorKey,
            createdAtUnixMs = local?.createdAtUnixMs ?: now,
            version = incoming.version,
            // A snapshot that lists us reopens the group: being removed and then
            // added back is exactly how a device recovers, and leaving it closed
            // would make the group readable but permanently mute.
            closed = false,
            avatarSeed = incoming.groupId,
        )
        // update, never insert-or-replace: replacing the row would cascade the group's
        // conversation — and every message in it — away on a re-sent snapshot.
        if (local == null) groupDao.insert(group) else groupDao.update(group)
        groupDao.replaceMembers(incoming.groupId, members, now)
        val conversationId = ensureConversation(incoming.groupId, now)
        if (local == null) writer.recordGroupEvent(conversationId, GroupEventText.created(group.name))
    }

    /** Only the creator holds the authoritative membership, so only the creator answers. */
    private suspend fun answerSnapshotRequest(incoming: Incoming, local: GroupEntity?) {
        if (local == null || local.creatorIdentityHash != incoming.selfKey) return
        controlSender.sendSnapshot(local, listOf(incoming.senderKey))
        AppLogger.info(TAG, "snapshot sent to ${incoming.senderKey} group=${incoming.groupId}")
    }

    private suspend fun applyLeave(incoming: Incoming, local: GroupEntity?) {
        if (local == null) return
        val member = groupDao.member(incoming.groupId, incoming.senderKey)
        if (member == null || member.removedAtUnixMs != null) return
        groupDao.markRemoved(incoming.groupId, incoming.senderKey, System.currentTimeMillis())
        conversationDao.getByGroupId(incoming.groupId)?.let {
            writer.recordGroupEvent(it.id, GroupEventText.left(member.displayName))
        }
    }

    /**
     * An incremental change is only meaningful in sequence: at `local + 1` it is
     * applied, above that a control was missed and a snapshot is requested, below
     * it is a replay and is ignored.
     */
    private suspend fun applyIncremental(incoming: Incoming, local: GroupEntity?) {
        if (local == null || incoming.senderKey != local.creatorIdentityHash) {
            AppLogger.warn(TAG, "control dropped: unknown group or non-creator sender group=${incoming.groupId}")
            return
        }
        val conversationId = conversationDao.getByGroupId(incoming.groupId)?.id
        if (incoming.version != local.version + 1 || conversationId == null) {
            if (incoming.version > local.version) controlSender.requestSnapshot(local, incoming.senderKey)
            AppLogger.info(TAG, "control out of order group=${incoming.groupId} v=${incoming.version}")
            return
        }
        apply(incoming, local, conversationId)
        groupDao.setVersion(incoming.groupId, incoming.version)
    }

    private suspend fun apply(incoming: Incoming, local: GroupEntity, conversationId: String) {
        when (incoming.control.type) {
            GroupControlType.GROUP_CONTROL_TYPE_UPDATE_NAME -> rename(incoming, conversationId)
            GroupControlType.GROUP_CONTROL_TYPE_ADD -> add(incoming, conversationId)
            GroupControlType.GROUP_CONTROL_TYPE_REMOVE -> remove(incoming, conversationId)
            GroupControlType.GROUP_CONTROL_TYPE_CLOSE -> {
                groupDao.setClosed(incoming.groupId, true)
                writer.recordGroupEvent(conversationId, GroupEventText.closed(local.name))
            }
            else -> AppLogger.warn(TAG, "unhandled control ${incoming.control.type} group=${incoming.groupId}")
        }
    }

    private suspend fun rename(incoming: Incoming, conversationId: String) {
        val name = incoming.control.name.take(GroupControlCodec.MAX_NAME_LENGTH)
        groupDao.setName(incoming.groupId, name, incoming.version)
        writer.recordGroupEvent(conversationId, GroupEventText.renamed(name))
    }

    /** The control carries the whole membership, so an ADD is a merge, not an append. */
    private suspend fun add(incoming: Incoming, conversationId: String) {
        val now = System.currentTimeMillis()
        val members = GroupControlCodec.members(incoming.control, incoming.groupId, incoming.creatorKey, now)
        if (members.size > MAX_GROUP_MEMBERS) {
            AppLogger.warn(TAG, "add dropped: over the member cap group=${incoming.groupId}")
            return
        }
        groupDao.upsertMembers(members)
        val added = incoming.control.targetIdentityHash.toStringUtf8()
        val name = members.firstOrNull { it.identityHash == added }?.displayName.orEmpty()
        writer.recordGroupEvent(conversationId, GroupEventText.added(name))
    }

    /**
     * Being the target is the end of the group for us: the copy stays readable but
     * goes read-only, which is the honest thing to show — we can no longer send,
     * and the others will stop sending to us.
     */
    private suspend fun remove(incoming: Incoming, conversationId: String) {
        val target = incoming.control.targetIdentityHash.toStringUtf8()
        val member = groupDao.member(incoming.groupId, target) ?: return
        groupDao.markRemoved(incoming.groupId, target, System.currentTimeMillis())
        if (target == incoming.selfKey) {
            groupDao.setClosed(incoming.groupId, true)
            writer.recordGroupEvent(conversationId, GroupEventText.REMOVED_ME)
        } else {
            writer.recordGroupEvent(conversationId, GroupEventText.removed(member.displayName))
        }
    }

    private suspend fun ensureConversation(groupId: String, now: Long): String {
        conversationDao.getByGroupId(groupId)?.let { return it.id }
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

    private companion object {
        const val TAG = "Groups"
    }
}
