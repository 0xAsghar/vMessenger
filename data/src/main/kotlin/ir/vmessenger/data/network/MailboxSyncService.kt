package ir.vmessenger.data.network

import dagger.Lazy
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.proto.app.v1.MailboxBlob
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.IncomingEnvelope
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls mailbox blobs addressed to us from a connected peer after the
 * handshake (rc29/rc31) and delivers them: open the sealed box with our own
 * static pair, verify the sender's signature, resolve the sender to a contact,
 * apply [InboundPolicy] and hand the inner envelope to
 * [IncomingMessageCollector.handleIncoming] without a session. The collector
 * is injected lazily because it owns the route that leads back here.
 */
@Singleton
class MailboxSyncService @Inject constructor(
    private val mailboxProtocolService: MailboxProtocolService,
    private val mailboxSeal: MailboxSeal,
    private val identityRepository: IdentityRepository,
    private val contactDao: ContactDao,
    private val collector: Lazy<IncomingMessageCollector>,
) {
    suspend fun pullFromPeer(session: ActiveSecureSession, self: PeerIdentity) {
        if (!P2PConfig.storeAndForwardEnabled) return
        mailboxProtocolService.requestList(session, self, self.identityHash)
    }

    suspend fun handleResponse(envelope: MessageEnvelope, session: ActiveSecureSession?) {
        if (!P2PConfig.storeAndForwardEnabled) return
        when {
            envelope.hasMailboxListResponse() -> {
                val ids = envelope.mailboxListResponse.blobIdsList
                if (session == null || ids.isEmpty()) return
                for (id in ids.take(MAX_FETCH_PER_SYNC)) {
                    mailboxProtocolService.requestFetch(session, id.toStringUtf8())
                }
            }
            envelope.hasMailboxFetchResponse() -> {
                val blob = envelope.mailboxFetchResponse.blob
                if (deliverLocalBlob(blob) && session != null) {
                    mailboxProtocolService.requestDelete(session, blob.blobId.toStringUtf8())
                }
            }
            else -> Unit
        }
    }

    /**
     * Opens, verifies and delivers a blob addressed to this identity (fetched
     * from a mailbox peer or pushed to us directly). Returns true when the
     * inner envelope reached the collector. Only chat envelopes are accepted
     * through the mailbox; infrastructure and receipt kinds are dropped.
     */
    suspend fun deliverLocalBlob(blob: MailboxBlob): Boolean {
        val opened = openForSelf(blob) ?: return false
        val contact = resolveSender(opened.senderIdentityPub)
        val kind = InboundKind.of(opened.envelope)
        val allowed = contact != null && kind == InboundKind.CHAT && InboundPolicy.allows(contact, kind)
        if (allowed && contact != null) {
            AppLogger.info(
                "Mailbox",
                "delivering via=mailbox messageId=${opened.envelope.messageId.toStringUtf8()} contact=${contact.id}",
            )
            collector.get().handleIncoming(IncomingEnvelope(opened.envelope, contact.id, session = null))
        } else {
            AppLogger.warn("Mailbox", "blob dropped: sender not allowed kind=${kind?.name} contact=${contact?.id}")
        }
        return allowed
    }

    private suspend fun openForSelf(blob: MailboxBlob): OpenedMailboxInner? {
        val identity = identityRepository.getIdentity()
        val addressed = blob.recipientIdentityHash.toByteArray()
        val forMe = P2PConfig.storeAndForwardEnabled &&
            identity != null &&
            IdentityHashMatcher.matches(identity.identityHash, addressed)
        val privateKey = if (forMe) identityRepository.getX25519StaticPrivateKey() else null
        if (identity == null || privateKey == null) {
            AppLogger.warn("Mailbox", "blob not deliverable here (addressed elsewhere or no static key)")
            return null
        }
        return try {
            mailboxSeal.open(
                sealedPayload = blob.sealedPayload.toByteArray(),
                recipientStaticPublic = identity.x25519StaticPublicKey,
                recipientStaticPrivate = privateKey,
                recipientHashCandidates = listOf(identity.identityHash, addressed),
            ).also { if (it == null) AppLogger.warn("Mailbox", "blob open/verify failed") }
        } finally {
            mailboxSeal.wipe(privateKey)
        }
    }

    /** The contact whose identity key signed the blob; null for strangers or a key/hash mismatch. */
    private suspend fun resolveSender(senderPub: ByteArray): ContactEntity? {
        val hash = UserHashEncoder.identityHashFromPublicKey(senderPub)
        val contact = contactDao.getByIdentityHash(hash)
            ?: contactDao.getByEd25519Public(senderPub)
            ?: contactDao.getByIdentityHash(IdentityHashMatcher.routingHash(hash))
        return contact?.takeIf {
            it.ed25519Public.contentEquals(senderPub) || IdentityHashMatcher.isPlaceholderPublicKey(it.ed25519Public)
        }
    }

    companion object {
        private const val MAX_FETCH_PER_SYNC = 10
    }
}
