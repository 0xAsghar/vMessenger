package ir.vmessenger.data.backup

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.LocationAccessEntity
import ir.vmessenger.core.proto.backup.v1.BackupContact
import ir.vmessenger.core.proto.backup.v1.BackupConversation
import ir.vmessenger.core.proto.backup.v1.BackupLocationAccess
import ir.vmessenger.core.proto.backup.v1.BackupMessage
import ir.vmessenger.core.proto.backup.v1.BackupNode
import ir.vmessenger.core.proto.backup.v1.BackupPayload
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.model.RestoreSummary
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the non-identity parts of a [BackupPayload] into the database with insert-or-ignore semantics:
 * existing contacts (by identity hash), conversations (by contact), messages (by id), location-access rows
 * and nodes are left untouched. Backup contact ids are remapped onto the ids actually present locally.
 *
 * Must run after the identity has been installed and, ideally, inside one transaction.
 */
@Singleton
class BackupRestoreWriter @Inject constructor(
    private val store: BackupStore,
) {
    suspend fun restore(payload: BackupPayload): RestoreSummary {
        val now = System.currentTimeMillis()
        val contacts = restoreContacts(payload.contactsList, now)
        val conversations = restoreConversations(payload.conversationsList, contacts.idMap, now)
        restoreLocationAccess(payload.locationAccessList, contacts.idMap, now)
        restoreNodes(payload.userNodesList)
        val summary = RestoreSummary(
            contacts = contacts.inserted,
            conversations = conversations.first,
            messages = conversations.second,
        )
        AppLogger.info(
            "Backup",
            "restored contacts=${summary.contacts} conversations=${summary.conversations} messages=${summary.messages}",
        )
        return summary
    }

    private class RestoredContacts(val idMap: Map<String, String>, val inserted: Int)

    private suspend fun restoreContacts(contacts: List<BackupContact>, now: Long): RestoredContacts {
        val idMap = HashMap<String, String>()
        var inserted = 0
        for (contact in contacts) {
            val (localId, created) = resolveContact(contact, now) ?: continue
            idMap[contact.id] = localId
            if (created) inserted++
        }
        return RestoredContacts(idMap, inserted)
    }

    /** Returns the local id for [contact] plus whether it was newly inserted; null when the entry is skipped. */
    private suspend fun resolveContact(contact: BackupContact, now: Long): Pair<String, Boolean>? {
        val identityHash = contact.identityHash.toByteArray()
        if (identityHash.size != BACKUP_IDENTITY_HASH_SIZE) {
            AppLogger.warn("Backup", "skipping contact with malformed identity hash id=${contact.id}")
            return null
        }
        val existing = store.contactDao.getByIdentityHash(identityHash)
        return when {
            existing != null -> existing.id to false
            else -> insertContact(contact, identityHash, now)?.let { it to true }
        }
    }

    private suspend fun insertContact(contact: BackupContact, identityHash: ByteArray, now: Long): String? {
        val mapped = contact.toContactEntity(identityHash, now)
        if (mapped == null) {
            AppLogger.warn("Backup", "skipping contact whose public key does not match its hash id=${contact.id}")
            return null
        }
        val entity = if (store.contactDao.getById(mapped.id) == null) {
            mapped
        } else {
            mapped.copy(id = UUID.randomUUID().toString())
        }
        store.contactDao.insert(entity)
        return entity.id
    }

    /** Returns (conversations created, messages inserted). */
    private suspend fun restoreConversations(
        conversations: List<BackupConversation>,
        idMap: Map<String, String>,
        now: Long,
    ): Pair<Int, Int> {
        var created = 0
        var messages = 0
        for (conversation in conversations) {
            val contactId = idMap[conversation.contactId] ?: continue
            val existing = store.conversationDao.getByContactId(contactId)
            val conversationId = existing?.id ?: insertConversation(conversation, contactId, now).also { created++ }
            messages += restoreMessages(conversationId, conversation.messagesList, now)
        }
        return created to messages
    }

    /**
     * Inserts a brand-new conversation row. `ConversationDao.upsert` is INSERT OR REPLACE, which would cascade-delete
     * the messages of an existing row, so callers must only reach this for contacts without a conversation.
     */
    private suspend fun insertConversation(conversation: BackupConversation, contactId: String, now: Long): String {
        val id = conversation.id.takeIf { it.isNotBlank() && store.conversationDao.getById(it) == null }
            ?: UUID.randomUUID().toString()
        val latest = conversation.messagesList.filter { it.messageId.isNotBlank() }.maxByOrNull { it.createdAtUnixMs }
        store.conversationDao.upsert(
            ConversationEntity(
                id = id,
                contactId = contactId,
                lastMessageId = latest?.messageId,
                lastActivityUnixMs = latest?.createdAtUnixMs?.takeIf { it > 0 } ?: now,
                unreadCount = 0,
                muted = conversation.muted,
            ),
        )
        return id
    }

    private suspend fun restoreMessages(conversationId: String, messages: List<BackupMessage>, now: Long): Int {
        var inserted = 0
        for (message in messages) {
            val entity = message.toMessageEntity(conversationId, now)
                ?.takeIf { store.messageDao.getById(it.messageId) == null }
                ?: continue
            store.messageDao.insert(entity)
            inserted++
        }
        return inserted
    }

    private suspend fun restoreLocationAccess(
        rows: List<BackupLocationAccess>,
        idMap: Map<String, String>,
        now: Long,
    ) {
        for (row in rows) {
            val contactId = idMap[row.contactId]
                ?.takeIf { store.locationAccessDao.getByContactId(it) == null }
                ?: continue
            store.locationAccessDao.upsert(
                LocationAccessEntity(
                    contactId = contactId,
                    canSeeMyLocation = row.canSeeMyLocation,
                    updatedAtUnixMs = now,
                ),
            )
        }
    }

    /** User-added nodes go through the normal add path, which validates the address and skips known ones. */
    private suspend fun restoreNodes(nodes: List<BackupNode>) {
        for (node in nodes) {
            val role = NetworkNodeRole.entries.firstOrNull { it.name == node.role } ?: continue
            store.nodeRepository.addNode(node.address, role)
        }
    }
}
