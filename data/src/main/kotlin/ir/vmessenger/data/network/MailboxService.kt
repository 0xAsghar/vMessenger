package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPath
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.database.dao.MailboxDao
import ir.vmessenger.core.database.entity.MailboxBlobEntity
import ir.vmessenger.core.proto.app.v1.MailboxBlob
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Store-and-forward for encrypted offline blobs (docs/P2P-Phases.md Phase 8).
 */
@Singleton
class MailboxService @Inject constructor(
    private val mailboxDao: MailboxDao,
) {
    suspend fun storeIncoming(blob: MailboxBlob) {
        if (!P2PConfig.storeAndForwardEnabled) return
        val now = System.currentTimeMillis()
        if (blob.expiresAtUnixMs <= now) return
        if (blob.sealedPayload.size() > MAX_BLOB_BYTES) return
        mailboxDao.purgeExpired(now)
        if (mailboxDao.countActive(now) >= MAX_TOTAL_BLOBS) {
            AppLogger.warn("Mailbox", "quota full; rejecting blob")
            return
        }
        mailboxDao.upsert(
            MailboxBlobEntity(
                blobId = blob.blobId.toStringUtf8(),
                recipientIdentityHash = blob.recipientIdentityHash.toByteArray(),
                sealedPayload = blob.sealedPayload.toByteArray(),
                expiresAtUnixMs = blob.expiresAtUnixMs,
                createdAtUnixMs = now,
            ),
        )
        AppLogger.info("Mailbox", "stored blob ${blob.blobId.toStringUtf8()}")
        updatePendingCount()
    }

    suspend fun offerPending(session: ActiveSecureSession, self: PeerIdentity, recipientHash: ByteArray) {
        if (!P2PConfig.storeAndForwardEnabled) return
        val now = System.currentTimeMillis()
        mailboxDao.purgeExpired(now)
        val pending = mailboxDao.forRecipient(recipientHash, now)
        for (entry in pending) {
            val envelope = MessageEnvelope.newBuilder()
                .setMessageId(ByteString.copyFromUtf8("mailbox-${entry.blobId}"))
                .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
                .setSentAtUnixMs(now)
                .setCounter(1)
                .setMailboxBlob(
                    MailboxBlob.newBuilder()
                        .setBlobId(ByteString.copyFromUtf8(entry.blobId))
                        .setRecipientIdentityHash(ByteString.copyFrom(entry.recipientIdentityHash))
                        .setSealedPayload(ByteString.copyFrom(entry.sealedPayload))
                        .setExpiresAtUnixMs(entry.expiresAtUnixMs),
                )
                .build()
            runCatching {
                val sealed = session.seal(envelope.toByteArray())
                val frame = Frame.newBuilder()
                    .setVersion(1)
                    .setType(FrameType.FRAME_TYPE_SECURE)
                    .setBody(ByteString.copyFrom(sealed))
                    .build()
                session.writeFrame(frame.toByteArray())
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

    suspend fun enqueueForRecipient(
        recipientHash: ByteArray,
        sealedPayload: ByteArray,
        ttlMs: Long = DEFAULT_TTL_MS,
    ) {
        if (!P2PConfig.storeAndForwardEnabled) return
        val now = System.currentTimeMillis()
        val blobId = java.util.UUID.randomUUID().toString()
        mailboxDao.upsert(
            MailboxBlobEntity(
                blobId = blobId,
                recipientIdentityHash = recipientHash,
                sealedPayload = sealedPayload,
                expiresAtUnixMs = now + ttlMs,
                createdAtUnixMs = now,
            ),
        )
        AppLogger.info("Mailbox", "queued local blob $blobId")
        updatePendingCount()
    }

    private suspend fun updatePendingCount() {
        val now = System.currentTimeMillis()
        mailboxDao.purgeExpired(now)
        NetworkPathTracker.setMailboxPendingCount(mailboxDao.countActive(now))
    }

    companion object {
        private const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_BLOB_BYTES = 256 * 1024
        private const val MAX_TOTAL_BLOBS = 200
    }
}
