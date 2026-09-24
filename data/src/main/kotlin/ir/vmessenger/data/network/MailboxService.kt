package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPath
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.database.dao.MailboxDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.MailboxBlobEntity
import ir.vmessenger.core.proto.app.v1.MailboxBlob
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Store-and-forward for sealed offline blobs.
 *
 * On by default ([P2PConfig.storeAndForwardEnabled]), and kept safe: only
 * APPROVED, non-blocked session peers may store blobs here, each sender is
 * capped per 24 h, ids are content-addressed and every payload is a
 * [MailboxSeal] box the storing peer cannot read or re-address.
 *
 * A blob's `expiresAtUnixMs` is what every holder purges by and refuses to hand
 * out past, so it is the one place a timed message's deadline can be enforced
 * away from the two ends: [enqueueForRecipient] never lets a parked copy
 * outlive the message it carries.
 */
@Singleton
class MailboxService @Inject constructor(
    private val mailboxDao: MailboxDao,
    private val identityRepository: IdentityRepository,
    private val mailboxSeal: MailboxSeal,
) {
    /**
     * Stores a blob the authenticated [sender] asked us to keep for a third
     * party. Returns false when refused (policy, quota, version, size, expiry).
     */
    suspend fun storeForSender(blob: MailboxBlob, sender: ContactEntity?): Boolean {
        val now = System.currentTimeMillis()
        mailboxDao.purgeExpired(now)
        val refusal = refusalReason(blob, sender, now)
        if (refusal != null || sender == null) {
            AppLogger.warn("Mailbox", "put rejected (${refusal ?: "no sender"}) from contact=${sender?.id}")
            return false
        }
        val sealedPayload = blob.sealedPayload.toByteArray()
        val blobId = mailboxSeal.blobId(sealedPayload)
        if (mailboxDao.getById(blobId) == null) {
            mailboxDao.upsert(
                MailboxBlobEntity(
                    blobId = blobId,
                    recipientIdentityHash = blob.recipientIdentityHash.toByteArray(),
                    sealedPayload = sealedPayload,
                    expiresAtUnixMs = blob.expiresAtUnixMs.coerceAtMost(now + DEFAULT_TTL_MS),
                    createdAtUnixMs = now,
                    senderIdentityHash = sender.identityHash,
                ),
            )
            AppLogger.info("Mailbox", "stored blob $blobId from contact=${sender.id}")
            updatePendingCount()
        }
        return true
    }

    /** Pushes blobs addressed to the session peer straight to them, then drops the local copy. */
    suspend fun offerPending(session: ActiveSecureSession, self: PeerIdentity, recipientHash: ByteArray) {
        if (!P2PConfig.storeAndForwardEnabled) return
        if (!IdentityHashMatcher.matches(session.peer.identityHash, recipientHash)) return
        val now = System.currentTimeMillis()
        mailboxDao.purgeExpired(now)
        val pending = mailboxDao.forRecipient(recipientHash, now)
        for (entry in pending) {
            val envelope = MessageEnvelope.newBuilder()
                .setMessageId(ByteString.copyFromUtf8("mailbox-${entry.blobId}"))
                .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
                .setSentAtUnixMs(now)
                .setCounter(1)
                .setMailboxBlob(entry.toProto())
                .build()
            runCatching {
                session.writeSealed(envelope)
                mailboxDao.delete(entry.blobId)
                NetworkPathTracker.record(
                    path = NetworkPath.STORE_AND_FORWARD,
                    detail = entry.blobId,
                )
            }.onFailure {
                AppLogger.warn("Mailbox", "offer failed ${entry.blobId}: ${it.message}")
            }
        }
    }

    /**
     * Seals [envelope] for [peer] with our identity key and queues it locally.
     * Returns false (nothing queued) when the flag is off, the peer's static
     * key is unknown, our own keys are unavailable, or the message has already
     * passed its self-destruct deadline — it would only be dropped unread.
     */
    suspend fun enqueueForRecipient(
        peer: PeerIdentity,
        envelope: MessageEnvelope,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Boolean {
        val now = System.currentTimeMillis()
        val expiresAt = parkedUntil(envelope, now, ttlMs)
        val sealed = if (expiresAt > now) sealForPeer(peer, envelope) else null
        if (sealed == null) return false
        val blobId = mailboxSeal.blobId(sealed.payload)
        mailboxDao.upsert(
            MailboxBlobEntity(
                blobId = blobId,
                recipientIdentityHash = peer.identityHash,
                sealedPayload = sealed.payload,
                expiresAtUnixMs = expiresAt,
                createdAtUnixMs = now,
                senderIdentityHash = sealed.senderIdentityHash,
            ),
        )
        AppLogger.info("Mailbox", "queued local blob $blobId")
        updatePendingCount()
        return true
    }

    private suspend fun sealForPeer(peer: PeerIdentity, envelope: MessageEnvelope): SealedForPeer? {
        val eligible = P2PConfig.storeAndForwardEnabled &&
            !IdentityHashMatcher.isPlaceholderPublicKey(peer.x25519StaticPublicKey)
        val identity = if (eligible) identityRepository.getIdentity() else null
        val privateKey = identity?.let { identityRepository.getEd25519PrivateKey() }
        if (identity == null || privateKey == null) {
            if (eligible) AppLogger.warn("Mailbox", "cannot seal: own identity unavailable")
            return null
        }
        val payload = mailboxSeal.seal(
            envelope = envelope,
            recipientIdentityHash = peer.identityHash,
            recipientStaticPublic = peer.x25519StaticPublicKey,
            senderIdentityPub = identity.ed25519PublicKey,
            senderEd25519Private = privateKey,
        )
        return SealedForPeer(payload, identity.identityHash)
    }

    private suspend fun refusalReason(blob: MailboxBlob, sender: ContactEntity?, now: Long): String? = when {
        !P2PConfig.storeAndForwardEnabled -> "disabled"
        sender == null || !canStoreFor(sender) -> "sender not approved"
        blob.sealVersion != MailboxSeal.SEAL_VERSION -> "seal version ${blob.sealVersion}"
        blob.expiresAtUnixMs <= now -> "expired"
        blob.sealedPayload.size() > MAX_BLOB_BYTES -> "too large"
        blob.recipientIdentityHash.size() != IDENTITY_HASH_BYTES -> "bad recipient hash"
        mailboxDao.countActive(now) >= MAX_TOTAL_BLOBS -> "quota full"
        mailboxDao.countBySenderSince(sender.identityHash, now - SENDER_WINDOW_MS) >= MAX_PER_SENDER ->
            "sender quota"
        else -> null
    }

    private fun canStoreFor(sender: ContactEntity): Boolean =
        sender.relationshipStatus == ContactRelationshipStatus.APPROVED && !sender.blocked

    private suspend fun updatePendingCount() {
        val now = System.currentTimeMillis()
        mailboxDao.purgeExpired(now)
        NetworkPathTracker.setMailboxPendingCount(mailboxDao.countActive(now))
    }

    private class SealedForPeer(val payload: ByteArray, val senderIdentityHash: ByteArray)

    /**
     * How long a parked copy of [envelope] may be held: the mailbox's own limit, or the message's
     * self-destruct deadline when that comes first. The copy used to take the full day regardless,
     * so a one-hour message could sit sealed on a holder long after it was gone from both ends.
     */
    private fun parkedUntil(envelope: MessageEnvelope, now: Long, ttlMs: Long): Long {
        val deadline = envelope.expiresAtUnixMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        return minOf(now + ttlMs, deadline)
    }

    companion object {
        const val MAX_BLOB_BYTES = 256 * 1024

        /** Blobs one authenticated sender may park here per rolling 24 h. */
        const val MAX_PER_SENDER = 20

        private const val SENDER_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_TOTAL_BLOBS = 200
        private const val IDENTITY_HASH_BYTES = 32
    }
}

/** Two services send these on the wire, so the mapping lives beside neither of them. */
internal fun MailboxBlobEntity.toProto(): MailboxBlob = MailboxBlob.newBuilder()
    .setBlobId(ByteString.copyFromUtf8(blobId))
    .setRecipientIdentityHash(ByteString.copyFrom(recipientIdentityHash))
    .setSealedPayload(ByteString.copyFrom(sealedPayload))
    .setExpiresAtUnixMs(expiresAtUnixMs)
    .setSealVersion(MailboxSeal.SEAL_VERSION)
    .build()
