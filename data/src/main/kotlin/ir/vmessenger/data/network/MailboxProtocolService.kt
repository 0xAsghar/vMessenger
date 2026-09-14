package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.MailboxDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.MailboxBlobEntity
import ir.vmessenger.core.proto.app.v1.MailboxBlob
import ir.vmessenger.core.proto.app.v1.MailboxDelete
import ir.vmessenger.core.proto.app.v1.MailboxDeleteAck
import ir.vmessenger.core.proto.app.v1.MailboxFetchRequest
import ir.vmessenger.core.proto.app.v1.MailboxFetchResponse
import ir.vmessenger.core.proto.app.v1.MailboxListRequest
import ir.vmessenger.core.proto.app.v1.MailboxListResponse
import ir.vmessenger.core.proto.app.v1.MailboxPut
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit mailbox protocol (Phase 8 / rc29). Every request is authorised
 * against the authenticated session peer: List/Fetch/Delete only ever touch
 * blobs addressed to that peer, and Put is accepted only from approved contacts.
 */
@Singleton
@Suppress("TooManyFunctions") // one request builder + one handler per mailbox verb
class MailboxProtocolService @Inject constructor(
    private val mailboxDao: MailboxDao,
    private val mailboxService: MailboxService,
    private val contactDao: ContactDao,
) {
    suspend fun requestFetch(session: ActiveSecureSession, blobId: String) {
        if (!P2PConfig.storeAndForwardEnabled) return
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-fetch-$blobId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxFetch(MailboxFetchRequest.newBuilder().setBlobId(ByteString.copyFromUtf8(blobId)))
            .build()
        sendEnvelope(session, envelope)
    }

    /** Asks the mailbox peer to drop a blob we have delivered locally. */
    suspend fun requestDelete(session: ActiveSecureSession, blobId: String) {
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-del-$blobId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxDelete(MailboxDelete.newBuilder().setBlobId(ByteString.copyFromUtf8(blobId)))
            .build()
        sendEnvelope(session, envelope)
    }

    suspend fun sendReply(session: ActiveSecureSession, reply: MessageEnvelope) {
        sendEnvelope(session, reply)
    }

    suspend fun handleIncoming(envelope: MessageEnvelope, session: ActiveSecureSession?): MessageEnvelope? {
        val reply = process(envelope, session?.peer)
        if (reply != null && session != null) {
            sendReply(session, reply)
        }
        return reply
    }

    /**
     * Ownership-checked core of [handleIncoming]; [peer] is the authenticated
     * session peer. Unauthenticated requests (no session) are ignored.
     */
    suspend fun process(envelope: MessageEnvelope, peer: PeerIdentity?): MessageEnvelope? {
        if (!P2PConfig.storeAndForwardEnabled || peer == null) return null
        return when {
            envelope.hasMailboxPut() -> handlePut(envelope, peer)
            envelope.hasMailboxList() -> handleList(envelope, peer)
            envelope.hasMailboxFetch() -> handleFetch(envelope, peer)
            envelope.hasMailboxDelete() -> handleDelete(envelope, peer)
            else -> null
        }
    }

    suspend fun requestList(session: ActiveSecureSession, self: PeerIdentity, recipientHash: ByteArray) {
        if (!P2PConfig.storeAndForwardEnabled) return
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-list-${System.currentTimeMillis()}"))
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxList(
                MailboxListRequest.newBuilder().setRecipientIdentityHash(ByteString.copyFrom(recipientHash)),
            )
            .build()
        sendEnvelope(session, envelope)
    }

    suspend fun putBlob(session: ActiveSecureSession, self: PeerIdentity, blob: MailboxBlob) {
        if (!P2PConfig.storeAndForwardEnabled) return
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-put-${blob.blobId.toStringUtf8()}"))
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxPut(MailboxPut.newBuilder().setBlob(blob))
            .build()
        sendEnvelope(session, envelope)
    }

    private suspend fun handlePut(envelope: MessageEnvelope, peer: PeerIdentity): MessageEnvelope? {
        mailboxService.storeForSender(envelope.mailboxPut.blob, resolveContact(peer))
        return null
    }

    private suspend fun handleList(envelope: MessageEnvelope, peer: PeerIdentity): MessageEnvelope {
        val requested = envelope.mailboxList.recipientIdentityHash.toByteArray()
        val now = System.currentTimeMillis()
        val ids = if (IdentityHashMatcher.matches(peer.identityHash, requested)) {
            mailboxDao.forRecipient(requested, now).map { ByteString.copyFromUtf8(it.blobId) }
        } else {
            AppLogger.warn("MailboxProtocol", "list denied: peer asked for another recipient")
            emptyList()
        }
        return MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-list-resp-$now"))
            .setSentAtUnixMs(now)
            .setCounter(1)
            .setMailboxListResponse(MailboxListResponse.newBuilder().addAllBlobIds(ids))
            .build()
    }

    private suspend fun handleFetch(envelope: MessageEnvelope, peer: PeerIdentity): MessageEnvelope? {
        val blobId = envelope.mailboxFetch.blobId.toStringUtf8()
        val entry = ownedBy(peer, blobId)
        if (entry == null) {
            AppLogger.warn("MailboxProtocol", "fetch denied blob=$blobId")
            return null
        }
        return MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-fetch-resp-$blobId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxFetchResponse(MailboxFetchResponse.newBuilder().setBlob(entry.toProto()))
            .build()
    }

    private suspend fun handleDelete(envelope: MessageEnvelope, peer: PeerIdentity): MessageEnvelope {
        val blobId = envelope.mailboxDelete.blobId.toStringUtf8()
        val entry = ownedBy(peer, blobId)
        if (entry != null) {
            mailboxDao.delete(blobId)
        } else {
            AppLogger.warn("MailboxProtocol", "delete denied blob=$blobId")
        }
        return MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-del-ack-$blobId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxDeleteAck(
                MailboxDeleteAck.newBuilder()
                    .setBlobId(envelope.mailboxDelete.blobId)
                    .setAccepted(entry != null),
            )
            .build()
    }

    /** The stored blob only if it is addressed to [peer]; null otherwise (denied or unknown). */
    private suspend fun ownedBy(peer: PeerIdentity, blobId: String): MailboxBlobEntity? =
        mailboxDao.getById(blobId)?.takeIf { IdentityHashMatcher.matches(peer.identityHash, it.recipientIdentityHash) }

    private suspend fun resolveContact(peer: PeerIdentity): ContactEntity? =
        contactDao.getByIdentityHash(peer.identityHash) ?: contactDao.getByEd25519Public(peer.ed25519PublicKey)

    private suspend fun sendEnvelope(session: ActiveSecureSession, envelope: MessageEnvelope) {
        runCatching {
            session.writeSealed(envelope)
        }.onFailure {
            AppLogger.warn("MailboxProtocol", "send failed: ${it.message}")
        }
    }

    companion object {
        const val MAX_BLOB_BYTES = MailboxService.MAX_BLOB_BYTES
    }
}
