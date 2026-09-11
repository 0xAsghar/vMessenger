package ir.vmessenger.data.backup

import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.LocationAccessDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.domain.repository.NodeManagementRepository
import javax.inject.Inject
import javax.inject.Singleton

/** The persistence handles a backup export/restore reads from and writes to (identity is handled separately). */
@Singleton
class BackupStore @Inject constructor(
    val contactDao: ContactDao,
    val conversationDao: ConversationDao,
    val messageDao: MessageDao,
    val locationAccessDao: LocationAccessDao,
    val nodeRepository: NodeManagementRepository,
)
