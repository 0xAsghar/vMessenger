package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.dao.GroupDao
import ir.vmessenger.core.database.dao.IdentityDao
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.database.entity.GroupMemberEntity
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.core.proto.app.v1.GroupControlType
import ir.vmessenger.data.repository.ConversationWriter
import javax.inject.Inject
import javax.inject.Singleton

/** One membership change on its way out: what it is, and who has to hear about it. */
data class GroupControlFanOutRequest(
    val group: GroupEntity,
    val conversationId: String,
    val type: GroupControlType,
    val members: List<GroupMemberEntity>,
    val version: Long,
    /** The member an ADD/REMOVE/LEAVE/SET_ROLE is about; null for the rest. */
    val target: String? = null,
    /** The role a SET_ROLE assigns; null for the rest. */
    val targetRole: GroupMemberRole? = null,
    /** The system line stored in the group's history for this change, in the app's language. */
    val systemText: String,
    /**
     * Someone who must receive the control although they are no longer a member —
     * the person being removed. Without it they would keep a group that simply
     * stopped answering.
     */
    val extraRecipient: String? = null,
)

/**
 * Builds a membership change and queues it to every member.
 *
 * It goes through the ordinary outbox, so a control reaches a member who is
 * offline right now exactly like a message would; the envelope is stored on the
 * queue row because it describes the group *at this version*, not as it will be
 * by the time a retry runs.
 */
@Singleton
class GroupControlFanOut @Inject constructor(
    private val groupDao: GroupDao,
    private val identityDao: IdentityDao,
    private val writer: ConversationWriter,
) {
    suspend fun send(request: GroupControlFanOutRequest): String? {
        val self = identityDao.getIdentity() ?: return null
        val selfKey = IdentityHashMatcher.routingKeyHex(self.identityHash)
        val envelope = GroupControlCodec.envelope(
            selfIdentityHash = self.identityHash,
            group = request.group,
            type = request.type,
            members = request.members,
            version = request.version,
            targetIdentityHash = request.target,
            targetRole = request.targetRole,
        )
        val recipients = recipients(request, selfKey)
        return writer.queueGroupControl(
            conversationId = request.conversationId,
            text = request.systemText,
            envelope = envelope.toByteArray(),
            recipients = recipients,
        )
    }

    private suspend fun recipients(request: GroupControlFanOutRequest, selfKey: String): List<String> =
        (groupDao.activeMembers(request.group.id).map { it.identityHash } + listOfNotNull(request.extraRecipient))
            .filterNot { it == selfKey }
            .distinct()
}
