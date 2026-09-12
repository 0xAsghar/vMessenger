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
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.notifications.ActiveConversationTracker
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.data.attachment.AttachmentFileStore
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
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

    suspend fun onBlocked(contactId: String) = withContext(ioDispatcher) {
        sessionCloser.closeSessions(contactId)
        locationSharingCoordinator.stopSharingWith(contactId)
        AppLogger.info("Contact", "blocked contact=$contactId: sessions closed, location sharing stopped")
    }

    /**
     * Tells the peer we removed them, once, bounded in time and never retried:
     * they may simply be offline, and the deletion must not wait on them. Skipped
     * for blocked contacts and for hash-only contacts we never handshaked with
     * (no key to authenticate a session against).
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
        val result = withTimeoutOrNull(REVOKE_TIMEOUT_MS) {
            contactRequestService.sendResponse(
                contactId = contact.id,
                peer = peer,
                requestId = requestId,
                type = ContactResponseType.CONTACT_RESPONSE_REVOKE,
            )
        }
        if (result is AppResult.Success) {
            AppLogger.info("Contact", "revoke sent contact=${contact.id}")
        } else {
            AppLogger.warn("Contact", "revoke not delivered contact=${contact.id} (best effort, not retried)")
        }
    }

    private suspend fun purgeConversation(conversationId: String) {
        val paths = messageDao.observeConversation(conversationId).first().mapNotNull { it.attachmentPath }
        var deleted = 0
        for (path in paths) {
            if (attachmentFileStore.delete(path)) deleted++
        }
        if (paths.isNotEmpty()) {
            AppLogger.info("Contact", "attachments removed $deleted/${paths.size} conversation=$conversationId")
        }
        outboxDao.removeByConversation(conversationId)
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
