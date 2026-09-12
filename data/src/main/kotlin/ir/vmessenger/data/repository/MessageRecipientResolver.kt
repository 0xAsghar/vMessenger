package ir.vmessenger.data.repository

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.GroupDao
import ir.vmessenger.core.database.dao.IdentityDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who a message in a conversation has to reach.
 *
 * A group is client-side fan-out, so it resolves to N recipients and a 1:1 chat to
 * exactly one — the same list shape, which is what lets one outbox pipeline serve
 * both. Recipients are identified by their **routing key** (the first 16 bytes of
 * the identity hash, lowercase hex): pairing by user hash only ever learns that
 * prefix, so it is the one key on which a group member and a contact row meet.
 *
 * Only contacts we can actually send to are returned (approved and not blocked);
 * a member who is not a contact of ours is skipped rather than queued forever.
 */
@Singleton
class MessageRecipientResolver @Inject constructor(
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val groupDao: GroupDao,
    private val identityDao: IdentityDao,
) {
    suspend fun resolve(conversationId: String): List<String> {
        val conversation = conversationDao.getById(conversationId) ?: return emptyList()
        val groupId = conversation.groupId
        return if (groupId == null) {
            listOfNotNull(conversation.contactId?.let { contactDao.getById(it) }?.takeIf { it.isSendable() })
                .map { it.routingKey() }
        } else {
            groupRecipients(groupId)
        }
    }

    /** Active members other than us that we hold a sendable contact for. */
    private suspend fun groupRecipients(groupId: String): List<String> {
        val self = selfRoutingKey()
        return groupDao.activeMembers(groupId)
            .map { it.identityHash }
            .filter { it != self }
            .filter { key -> contactDao.getByRoutingKey(key)?.isSendable() == true }
    }

    suspend fun selfRoutingKey(): String? =
        identityDao.getIdentity()?.identityHash?.let(IdentityHashMatcher::routingKeyHex)

    private fun ContactEntity.isSendable(): Boolean =
        !blocked && relationshipStatus == ContactRelationshipStatus.APPROVED

    private fun ContactEntity.routingKey(): String = IdentityHashMatcher.routingKeyHex(identityHash)
}
