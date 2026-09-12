package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.GroupDao
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where an inbound message belongs, and who it is from.
 *
 * [senderIdentityHash] is set only for a group message: in a 1:1 chat the
 * conversation already says who the sender is, and storing it there would be
 * noise the bubble has to ignore.
 */
data class InboundTarget(
    val conversationId: String,
    val senderIdentityHash: String?,
    /** Group name for the notification title; null for a 1:1 chat. */
    val groupName: String?,
)

/**
 * Decides which conversation an authenticated envelope is written to.
 *
 * The group case is where this earns its keep. `group_id` is peer-controlled, so
 * being an approved contact is not enough: the group must exist here, must not be
 * closed, and the sender must be one of its active members. Otherwise a single
 * contact could write into any group whose id they ever saw — or into one they
 * were removed from.
 */
@Singleton
class InboundConversationResolver @Inject constructor(
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val groupDao: GroupDao,
) {
    /** Null when the envelope may not be written anywhere; the caller drops it. */
    suspend fun resolve(contactId: String, envelope: MessageEnvelope, now: Long): InboundTarget? {
        val groupId = GroupControlCodec.groupIdOf(envelope) ?: return direct(contactId, now)
        return group(contactId, groupId)
    }

    private suspend fun direct(contactId: String, now: Long): InboundTarget {
        val existing = conversationDao.getByContactId(contactId)
        val id = existing?.id ?: createDirect(contactId, now)
        return InboundTarget(conversationId = id, senderIdentityHash = null, groupName = null)
    }

    private suspend fun group(contactId: String, groupId: String): InboundTarget? {
        val senderKey = contactDao.getById(contactId)?.identityHash?.let(IdentityHashMatcher::routingKeyHex)
        val group = groupDao.getById(groupId)
        val conversationId = conversationDao.getByGroupId(groupId)?.id
        val member = senderKey?.let { groupDao.member(groupId, it) }
        val allowed = group != null &&
            !group.closed &&
            conversationId != null &&
            member != null &&
            member.removedAtUnixMs == null
        if (!allowed) {
            AppLogger.warn(TAG, "group message dropped: contact=$contactId is not an active member of $groupId")
            return null
        }
        return InboundTarget(
            conversationId = requireNotNull(conversationId),
            senderIdentityHash = senderKey,
            groupName = group?.name,
        )
    }

    private suspend fun createDirect(contactId: String, now: Long): String {
        val id = UUID.randomUUID().toString()
        conversationDao.upsert(
            ConversationEntity(
                id = id,
                contactId = contactId,
                groupId = null,
                lastMessageId = null,
                lastActivityUnixMs = now,
                unreadCount = 0,
                muted = false,
            ),
        )
        return id
    }

    private companion object {
        const val TAG = "Messaging"
    }
}
