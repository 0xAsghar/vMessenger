package ir.vmessenger.data.network

import dagger.Lazy
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ContactRequestDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.EndpointCacheDao
import ir.vmessenger.core.database.dao.LocationAccessDao
import ir.vmessenger.core.database.dao.LocationShareDao
import ir.vmessenger.core.database.dao.MailboxDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.dao.PendingRevokeDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.PendingRevokeEntity
import ir.vmessenger.core.notifications.ActiveConversationTracker
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.data.attachment.AttachmentFileStore
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.data.repository.ConversationDraftStore
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Closes every live secure session with a contact; an interface so cleanup is testable without a transport. */
interface SessionCloser {
    suspend fun closeSessions(contactId: String)
}

@Singleton
class MessagingSessionCloser @Inject constructor(
    // Lazy keeps this edge from ever closing a Hilt cycle through the inbound pipeline.
    private val messagingService: Lazy<MessagingService>,
) : SessionCloser {
    override suspend fun closeSessions(contactId: String) = messagingService.get().closeSessions(contactId)
}

/**
 * Everything that has to happen when a contact is deleted or blocked, in the
 * order the `ContactRepository.deleteContact` contract fixes:
 *
 * 1. live sessions closed (and outgoing/incoming location shares stopped);
 * 2. one best-effort `CONTACT_RESPONSE_REVOKE` so the peer sees us as gone;
 * 3. the conversation's attachment files deleted;
 * 4. queued outbox rows removed;
 * 5. location shares (samples cascade) and location access removed;
 * 6. cached endpoints, mailbox blobs held for the peer and their contact requests removed;
 * 7. the contact row deleted (conversation and messages cascade);
 * 8. the active-conversation tracker cleared if it pointed at that chat.
 *
 * Blocking runs step 1 only: outbox rows stay (the dispatcher skips blocked
 * contacts) so unblocking resumes where things left off.
 */
@Singleton
@Suppress("LongParameterList") // one dependency per store the contact's derived state lives in
class ContactCleanupCoordinator @Inject constructor(
    private val sessionCloser: SessionCloser,
    private val contactRequestService: ContactRequestService,
    private val locationSharingCoordinator: LocationSharingCoordinator,
    private val attachmentFileStore: AttachmentFileStore,
    private val identityRepository: IdentityRepository,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val locationShareDao: LocationShareDao,
    private val locationAccessDao: LocationAccessDao,
    private val endpointCacheDao: EndpointCacheDao,
    private val mailboxDao: MailboxDao,
    private val contactRequestDao: ContactRequestDao,
    private val pendingRevokeDao: PendingRevokeDao,
    private val draftStore: ConversationDraftStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun deleteContact(contactId: String) = withContext(ioDispatcher) {
        val contact = contactDao.getById(contactId) ?: return@withContext
        val conversation = conversationDao.getByContactId(contactId)
        sessionCloser.closeSessions(contactId)
        locationSharingCoordinator.stopSharingWith(contactId)
        sendRevoke(contact)
        // The revoke opened a fresh session; nothing may stay up for a contact that no longer exists.
        sessionCloser.closeSessions(contactId)
        conversation?.let { purgeConversation(it.id) }
        locationShareDao.deleteByContact(contactId)
        locationAccessDao.deleteByContactId(contactId)
        endpointCacheDao.delete(IdentityHashMatcher.routingHash(contact.identityHash))
        for (hash in hashForms(contact.identityHash)) {
            mailboxDao.deleteForRecipient(hash)
            contactRequestDao.deleteByRequesterHash(hash)
        }
        contactDao.deleteById(contactId)
        conversation?.let { ActiveConversationTracker.clear(it.id) }
        AppLogger.info("Contact", "deleted contact=$contactId")
    }

    /**
     * Forgets a revoke still waiting to reach this peer, because the user has just taken them back.
     *
     * Left queued, it raced the new contact request once the peer came online. When the request won,
     * the peer auto-accepted it and only *then* processed the revoke: we showed them as approved,
     * they had us as rejected, and neither side would ever ask again. Matched on the routing prefix,
     * since a contact re-added by user hash knows no more than that.
     */
    suspend fun cancelPendingRevoke(identityHash: ByteArray) = withContext(ioDispatcher) {
        pendingRevokeDao.deleteByRoutingKey(IdentityHashMatcher.routingKeyHex(identityHash))
    }

    suspend fun onBlocked(contactId: String) = withContext(ioDispatcher) {
        sessionCloser.closeSessions(contactId)
        locationSharingCoordinator.stopSharingWith(contactId)
        AppLogger.info("Contact", "blocked contact=$contactId: sessions closed, location sharing stopped")
    }

    /**
     * Tells the peer we removed them — now if they are reachable, and otherwise for as long as it
     * takes.
     *
     * This used to be one attempt, bounded at ten seconds, never retried. People delete contacts
     * they are *not* currently talking to, so the usual outcome was a peer who was offline and
     * never found out: they kept us approved indefinitely, their messages were dropped by the
     * inbound policy with only a log line, and their own outbox showed one tick forever. The
     * deletion still does not wait on them — it is queued first, so the row can go — but the queue
     * outlives the contact and [PendingRevokeWorker] keeps trying.
     *
     * Skipped for blocked contacts, and for hash-only contacts we never handshaked with: there is
     * no key to authenticate a session against, so there is nobody to tell.
     */
    private suspend fun sendRevoke(contact: ContactEntity) {
        if (contact.blocked || IdentityHashMatcher.isPlaceholderPublicKey(contact.ed25519Public)) return
        val self = identityRepository.getIdentity() ?: return
        val peer = PeerIdentity(
            identityHash = contact.identityHash,
            ed25519PublicKey = contact.ed25519Public,
            x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(X25519_KEY_SIZE),
        )
        // The id of *their* request to us, which is what the receiver validates a response against.
        val requestId = ContactRequestService.deterministicRequestId(contact.identityHash, self.identityHash)
        pendingRevokeDao.upsert(
            PendingRevokeEntity(
                identityHash = contact.identityHash,
                ed25519Public = contact.ed25519Public,
                x25519StaticPublic = contact.x25519StaticPublic,
                requestId = requestId,
                createdAtUnixMs = System.currentTimeMillis(),
            ),
        )
        val result = withTimeoutOrNull(REVOKE_TIMEOUT_MS) {
            contactRequestService.sendResponse(
                contactId = contact.id,
                peer = peer,
                requestId = requestId,
                type = ContactResponseType.CONTACT_RESPONSE_REVOKE,
            )
        }
        if (result is AppResult.Success) {
            pendingRevokeDao.delete(contact.identityHash)
            AppLogger.info("Contact", "revoke sent contact=${contact.id}")
        } else {
            AppLogger.info("Contact", "revoke queued for retry contact=${contact.id}")
        }
    }

    private suspend fun purgeConversation(conversationId: String) {
        // One query for the paths; materialising every message just to read them
        // is wasteful on a long thread.
        val paths = messageDao.attachmentPaths(conversationId)
        var deleted = 0
        for (path in paths) {
            if (attachmentFileStore.delete(path)) deleted++
        }
        if (paths.isNotEmpty()) {
            AppLogger.info("Contact", "attachments removed $deleted/${paths.size} conversation=$conversationId")
        }
        outboxDao.removeByConversation(conversationId)
        // An unsent draft would otherwise outlive the contact it was addressed to.
        draftStore.clear(conversationId)
    }

    /** A hash-added contact may be stored under its 16-byte routing prefix; both forms are purged. */
    private fun hashForms(identityHash: ByteArray): List<ByteArray> {
        val routing = IdentityHashMatcher.routingHash(identityHash)
        return if (routing.contentEquals(identityHash)) listOf(identityHash) else listOf(identityHash, routing)
    }

    private companion object {
        const val REVOKE_TIMEOUT_MS = 10_000L
        const val X25519_KEY_SIZE = 32
    }
}
