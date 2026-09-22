package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.database.dao.ContactRequestDao
import ir.vmessenger.core.database.dao.EndpointCacheDao
import ir.vmessenger.core.database.dao.MailboxDao
import ir.vmessenger.core.database.dao.PendingRevokeDao
import ir.vmessenger.core.database.entity.ContactRequestEntity
import ir.vmessenger.core.database.entity.EndpointCacheEntity
import ir.vmessenger.core.database.entity.MailboxBlobEntity
import ir.vmessenger.core.database.entity.PendingRevokeEntity
import ir.vmessenger.data.activity.testActivityLogger
import ir.vmessenger.data.attachment.AttachmentFileStore
import ir.vmessenger.data.repository.FakeContactDao
import ir.vmessenger.data.repository.FakeConversationDao
import ir.vmessenger.data.repository.FakeConversationDraftStore
import ir.vmessenger.data.repository.FakeIdentityRepository
import ir.vmessenger.data.repository.FakeLocationAccessDao
import ir.vmessenger.data.repository.FakeMessageDao
import ir.vmessenger.data.repository.LocationRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeSessionCloser : SessionCloser {
    val closed = mutableListOf<String>()

    override suspend fun closeSessions(contactId: String) {
        closed += contactId
    }
}

class FakeAttachmentFileStore : AttachmentFileStore {
    val deleted = mutableListOf<String>()

    override fun delete(path: String): Boolean {
        deleted += path
        return true
    }
}

class FakeEndpointCacheDao : EndpointCacheDao {
    val entries = mutableListOf<EndpointCacheEntity>()

    override suspend fun upsert(entity: EndpointCacheEntity) {
        entries.removeAll { it.identityHash.contentEquals(entity.identityHash) }
        entries += entity
    }

    override suspend fun get(hash: ByteArray): EndpointCacheEntity? =
        entries.firstOrNull { it.identityHash.contentEquals(hash) }

    override suspend fun purgeExpired(now: Long) {
        entries.removeAll { it.expiresAtUnixMs < now }
    }

    override suspend fun delete(hash: ByteArray) {
        entries.removeAll { it.identityHash.contentEquals(hash) }
    }
}

class FakePendingRevokeDao : PendingRevokeDao {
    val queued = mutableListOf<PendingRevokeEntity>()

    override suspend fun upsert(revoke: PendingRevokeEntity) {
        queued.removeAll { it.identityHash.contentEquals(revoke.identityHash) }
        queued += revoke
    }

    override suspend fun update(revoke: PendingRevokeEntity) = upsert(revoke)

    override suspend fun due(now: Long): List<PendingRevokeEntity> = queued.filter { it.nextAttemptUnixMs <= now }

    override suspend fun delete(identityHash: ByteArray) {
        queued.removeAll { it.identityHash.contentEquals(identityHash) }
    }

    override suspend fun deleteByRoutingKey(routingKeyHex: String) {
        queued.removeAll { IdentityHashMatcher.routingKeyHex(it.identityHash) == routingKeyHex }
    }

    override suspend fun purgeOlderThan(cutoff: Long) {
        queued.removeAll { it.createdAtUnixMs < cutoff }
    }
}

class FakeMailboxDao : MailboxDao {
    val blobs = mutableListOf<MailboxBlobEntity>()

    override suspend fun upsert(blob: MailboxBlobEntity) {
        blobs.removeAll { it.blobId == blob.blobId }
        blobs += blob
    }

    override suspend fun forRecipient(hash: ByteArray, now: Long): List<MailboxBlobEntity> =
        blobs.filter { it.recipientIdentityHash.contentEquals(hash) && it.expiresAtUnixMs > now }

    override suspend fun ownBlobsForOthers(
        sender: ByteArray,
        exclude: ByteArray,
        now: Long,
        limit: Int,
    ): List<MailboxBlobEntity> = blobs
        .filter { it.senderIdentityHash?.contentEquals(sender) == true }
        .filterNot { it.recipientIdentityHash.contentEquals(exclude) }
        .filter { it.expiresAtUnixMs > now }
        .sortedByDescending { it.createdAtUnixMs }
        .take(limit)

    override suspend fun getById(blobId: String): MailboxBlobEntity? = blobs.firstOrNull { it.blobId == blobId }

    override suspend fun countActive(now: Long): Int = blobs.count { it.expiresAtUnixMs > now }

    override suspend fun countBySenderSince(sender: ByteArray, since: Long): Int =
        blobs.count { it.senderIdentityHash?.contentEquals(sender) == true && it.createdAtUnixMs >= since }

    override suspend fun delete(blobId: String) {
        blobs.removeAll { it.blobId == blobId }
    }

    override suspend fun deleteForRecipient(hash: ByteArray) {
        blobs.removeAll { it.recipientIdentityHash.contentEquals(hash) }
    }

    override suspend fun purgeExpired(now: Long) {
        blobs.removeAll { it.expiresAtUnixMs <= now }
    }
}

class FakeContactRequestDao : ContactRequestDao {
    val requests = mutableListOf<ContactRequestEntity>()

    override fun observePending(): Flow<List<ContactRequestEntity>> = flowOf(requests.toList())

    override suspend fun getById(requestId: String): ContactRequestEntity? =
        requests.firstOrNull { it.requestId == requestId }

    override suspend fun getByRequesterHash(hash: ByteArray): ContactRequestEntity? =
        requests.firstOrNull { it.requesterIdentityHash.contentEquals(hash) }

    override suspend fun rejectCountOf(requestId: String): Int? = getById(requestId)?.rejectCount

    override suspend fun upsert(entity: ContactRequestEntity) {
        requests.removeAll { it.requestId == entity.requestId }
        requests += entity
    }

    override suspend fun update(entity: ContactRequestEntity) {
        requests.replaceAll { if (it.requestId == entity.requestId) entity else it }
    }

    override suspend fun deleteById(requestId: String) {
        requests.removeAll { it.requestId == requestId }
    }

    override suspend fun deleteByRequesterHash(hash: ByteArray) {
        requests.removeAll { it.requesterIdentityHash.contentEquals(hash) }
    }
}

/**
 * Every fake the cleanup path touches, wired into a real [LocationSharingCoordinator]
 * and [ContactCleanupCoordinator]; tests inspect the fakes after acting.
 */
class CleanupHarness(val contactDao: FakeContactDao = FakeContactDao()) {
    val cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    val identityRepository = FakeIdentityRepository(cryptoEngine)
        .apply { InboundFixtures.installIdentity(this, 0x01) }
    val selfIdentityCache = SelfIdentityCache(identityRepository, cryptoEngine)
    val conversationDao = FakeConversationDao()
    val messageDao = FakeMessageDao()
    val outboxDao = FakeOutboxDao()
    val shareDao = FakeLocationShareDao()
    val sampleDao = FakeLocationSampleDao()
    val locationAccessDao = FakeLocationAccessDao()
    val locationAccessRepository = FakeLocationAccessRepository()
    val endpointCacheDao = FakeEndpointCacheDao()
    val mailboxDao = FakeMailboxDao()
    val contactRequestDao = FakeContactRequestDao()
    val pendingRevokeDao = FakePendingRevokeDao()
    val messaging = FakeMessagingPort()
    val sessionCloser = FakeSessionCloser()
    val attachmentStore = FakeAttachmentFileStore()
    val draftStore = FakeConversationDraftStore()
    val serviceControl = FakeLocationServiceControl()

    val locationSharing = LocationSharingCoordinator(
        locationRepository = LocationRepositoryImpl(shareDao, sampleDao, contactDao, testActivityLogger()),
        locationAccessRepository = locationAccessRepository,
        locationShareDao = shareDao,
        locationSampleDao = sampleDao,
        contactDao = contactDao,
        selfIdentityCache = selfIdentityCache,
        messaging = messaging,
        locationServiceControl = serviceControl,
        ioDispatcher = Dispatchers.Unconfined,
    )

    val coordinator = ContactCleanupCoordinator(
        sessionCloser = sessionCloser,
        contactRequestService = ContactRequestService(
            identityRepository,
            selfIdentityCache,
            messaging,
            ContactRequestRetryBudget(ContactRequestRetryStore.Transient),
            Dispatchers.Unconfined,
        ),
        locationSharingCoordinator = locationSharing,
        attachmentFileStore = attachmentStore,
        identityRepository = identityRepository,
        contactDao = contactDao,
        conversationDao = conversationDao,
        messageDao = messageDao,
        outboxDao = outboxDao,
        locationShareDao = shareDao,
        locationAccessDao = locationAccessDao,
        endpointCacheDao = endpointCacheDao,
        mailboxDao = mailboxDao,
        contactRequestDao = contactRequestDao,
        pendingRevokeDao = pendingRevokeDao,
        draftStore = draftStore,
        ioDispatcher = Dispatchers.Unconfined,
    )
}
