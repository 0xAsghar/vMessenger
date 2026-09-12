package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.network.NodeTrust
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.ConversationWithPreview
import ir.vmessenger.core.database.dao.LocationAccessDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.LocationAccessEntity
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.data.backup.TransactionRunner
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.repository.NodeManagementRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory stand-in for the Keystore-backed repository; keeps plaintext keys so a test can compare them. */
@Suppress("TooManyFunctions") // mirrors the full IdentityRepository contract
class FakeIdentityRepository(private val cryptoEngine: CryptoEngine) : IdentityRepository {
    var identity: Identity? = null
    var ed25519Private: ByteArray? = null
    var x25519StaticPrivate: ByteArray? = null

    override fun observeIdentity(): Flow<Identity?> = flowOf(identity)

    override suspend fun getIdentity(): Identity? = identity

    override suspend fun hasIdentity(): Boolean = identity != null

    override suspend fun generateIdentity(displayName: String): AppResult<Identity> {
        val ed25519 = cryptoEngine.generateEd25519KeyPair()
        val x25519 = cryptoEngine.generateX25519KeyPair()
        return importIdentity(
            ed25519.publicKey,
            ed25519.privateKey,
            x25519.publicKey,
            x25519.privateKey,
            displayName,
            System.currentTimeMillis(),
        )
    }

    @Suppress("LongParameterList")
    override suspend fun importIdentity(
        ed25519Public: ByteArray,
        ed25519Private: ByteArray,
        x25519StaticPublic: ByteArray,
        x25519StaticPrivate: ByteArray,
        displayName: String,
        createdAtUnixMs: Long,
    ): AppResult<Identity> {
        if (identity != null) return AppResult.Error(AppError.Validation("identity exists"))
        val identityHash = UserHashEncoder.identityHashFromPublicKey(ed25519Public)
        val created = Identity(
            ed25519PublicKey = ed25519Public.copyOf(),
            identityHash = identityHash,
            userHash = UserHashEncoder.encode(identityHash),
            displayName = displayName,
            x25519StaticPublicKey = x25519StaticPublic.copyOf(),
            createdAtUnixMs = createdAtUnixMs,
        )
        identity = created
        this.ed25519Private = ed25519Private.copyOf()
        this.x25519StaticPrivate = x25519StaticPrivate.copyOf()
        cryptoEngine.memzero(ed25519Private)
        cryptoEngine.memzero(x25519StaticPrivate)
        return AppResult.Success(created)
    }

    override suspend fun updateDisplayName(displayName: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getEd25519PrivateKey(): ByteArray? = ed25519Private?.copyOf()

    override suspend fun getX25519StaticPrivateKey(): ByteArray? = x25519StaticPrivate?.copyOf()

    override suspend fun wipeIdentity() {
        identity = null
        ed25519Private = null
        x25519StaticPrivate = null
    }
}

class FakeContactDao : ContactDao {
    val contacts = mutableListOf<ContactEntity>()

    override fun observeContacts(): Flow<List<ContactEntity>> = flowOf(contacts.filter { !it.blocked })

    override suspend fun getById(id: String): ContactEntity? = contacts.firstOrNull { it.id == id }

    override suspend fun getByIdentityHash(identityHash: ByteArray): ContactEntity? =
        contacts.firstOrNull { it.identityHash.contentEquals(identityHash) }

    override suspend fun getByEd25519Public(ed25519Public: ByteArray): ContactEntity? =
        contacts.firstOrNull { it.ed25519Public.contentEquals(ed25519Public) }

    override suspend fun getAll(): List<ContactEntity> = contacts.toList()

    override suspend fun touchLastSeen(id: String, ts: Long) = Unit

    override suspend fun recordPendingKeyChange(id: String, staticPub: ByteArray, ts: Long) {
        contacts.replaceAll {
            if (it.id == id) it.copy(pendingX25519StaticPublic = staticPub, keyChangedAtUnixMs = ts) else it
        }
    }

    override suspend fun insert(entity: ContactEntity) {
        check(contacts.none { it.id == entity.id || it.identityHash.contentEquals(entity.identityHash) }) {
            "constraint violation for contact ${entity.id}"
        }
        contacts += entity
    }

    override suspend fun update(entity: ContactEntity) {
        contacts.replaceAll { if (it.id == entity.id) entity else it }
    }

    override suspend fun deleteById(id: String) {
        contacts.removeAll { it.id == id }
    }

    override suspend fun deleteAll() = contacts.clear()
}

class FakeConversationDao : ConversationDao {
    val conversations = mutableListOf<ConversationEntity>()
    var upsertCalls = 0

    override suspend fun upsert(entity: ConversationEntity) {
        upsertCalls++
        conversations.removeAll { it.id == entity.id }
        conversations += entity
    }

    override suspend fun update(entity: ConversationEntity) {
        conversations.replaceAll { if (it.id == entity.id) entity else it }
    }

    override fun observeAll(): Flow<List<ConversationEntity>> = flowOf(conversations.toList())

    override fun observeAllWithPreview(): Flow<List<ConversationWithPreview>> =
        flowOf(conversations.map { ConversationWithPreview(it, null) })

    override suspend fun getById(id: String): ConversationEntity? = conversations.firstOrNull { it.id == id }

    override suspend fun getByContactId(contactId: String): ConversationEntity? =
        conversations.firstOrNull { it.contactId == contactId }

    override suspend fun resetUnread(id: String) {
        conversations.replaceAll { if (it.id == id) it.copy(unreadCount = 0) else it }
    }
}

class FakeMessageDao : MessageDao {
    val messages = mutableListOf<MessageEntity>()

    /** INSERT OR IGNORE semantics, like the real DAO. */
    override suspend fun insert(message: MessageEntity) {
        if (messages.none { it.messageId == message.messageId }) messages += message
    }

    override fun observeConversation(cid: String): Flow<List<MessageEntity>> =
        flowOf(messages.filter { it.conversationId == cid }.sortedBy { it.createdAtUnixMs })

    override suspend fun markDelivered(id: String, status: DeliveryStatus, ts: Long) =
        replace(id) { it.copy(status = status, deliveredAtUnixMs = ts) }

    override suspend fun markRead(id: String, status: DeliveryStatus, ts: Long) =
        replace(id) { it.copy(status = status, readAtUnixMs = ts) }

    override suspend fun markSent(id: String, status: DeliveryStatus, ts: Long) =
        replace(id) { it.copy(status = status, sentAtUnixMs = ts) }

    override suspend fun updateStatus(id: String, status: DeliveryStatus) =
        replace(id) { it.copy(status = status) }

    override suspend fun getById(id: String): MessageEntity? = messages.firstOrNull { it.messageId == id }

    override suspend fun getByIdInConversation(id: String, cid: String): MessageEntity? =
        messages.firstOrNull { it.messageId == id && it.conversationId == cid }

    override suspend fun selectUnreadIncomingIds(cid: String): List<String> =
        messages.filter { it.conversationId == cid && it.isUnreadIncoming() }.map { it.messageId }

    override suspend fun markIncomingRead(cid: String, ts: Long) {
        messages.replaceAll {
            if (it.conversationId == cid && it.isUnreadIncoming()) {
                it.copy(status = DeliveryStatus.READ, readAtUnixMs = ts)
            } else {
                it
            }
        }
    }

    private fun MessageEntity.isUnreadIncoming(): Boolean =
        direction == MessageDirection.INCOMING && status != DeliveryStatus.READ

    private fun replace(id: String, transform: (MessageEntity) -> MessageEntity) {
        messages.replaceAll { if (it.messageId == id) transform(it) else it }
    }
}

class FakeLocationAccessDao : LocationAccessDao {
    val rows = mutableListOf<LocationAccessEntity>()

    override fun observeGranted(): Flow<List<LocationAccessEntity>> = flowOf(rows.filter { it.canSeeMyLocation })

    override fun observeAll(): Flow<List<LocationAccessEntity>> = flowOf(rows.toList())

    override suspend fun getByContactId(contactId: String): LocationAccessEntity? =
        rows.firstOrNull { it.contactId == contactId }

    override suspend fun grantedContactIds(): List<String> = rows.filter { it.canSeeMyLocation }.map { it.contactId }

    override suspend fun upsert(entity: LocationAccessEntity) {
        rows.removeAll { it.contactId == entity.contactId }
        rows += entity
    }

    override suspend fun deleteByContactId(contactId: String) {
        rows.removeAll { it.contactId == contactId }
    }
}

class FakeNodeRepository : NodeManagementRepository {
    val nodes = mutableListOf<NetworkNode>()

    override fun observeNodes(): Flow<List<NetworkNode>> = flowOf(nodes.toList())

    override suspend fun addNode(input: String, fallbackRole: NetworkNodeRole): AppResult<NetworkNode> {
        val node = NetworkNode(
            address = input,
            role = fallbackRole,
            source = NetworkNode.SOURCE_USER,
            enabled = true,
            lastOkUnixMs = null,
            lastFailUnixMs = null,
            failCount = 0,
            trust = NodeTrust.USER,
        )
        if (nodes.none { it.address == input && it.role == fallbackRole }) nodes += node
        return AppResult.Success(node)
    }

    override suspend fun setEnabled(address: String, role: NetworkNodeRole, enabled: Boolean) = Unit

    override suspend fun removeNode(address: String, role: NetworkNodeRole): AppResult<Unit> = AppResult.Success(Unit)

    override fun exportLink(node: NetworkNode): String = "vmnode:${node.role.name.lowercase()}:${node.address}"
}

/** Runs the block directly; the fakes have no transaction concept. */
object PassThroughTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}
