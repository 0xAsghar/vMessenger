package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.database.dao.MailboxDao
import ir.vmessenger.core.proto.app.v1.MailboxBlob
import ir.vmessenger.core.proto.app.v1.MailboxDelete
import ir.vmessenger.core.proto.app.v1.MailboxDeleteAck
import ir.vmessenger.core.proto.app.v1.MailboxFetchRequest
import ir.vmessenger.core.proto.app.v1.MailboxFetchResponse
import ir.vmessenger.core.proto.app.v1.MailboxListRequest
import ir.vmessenger.core.proto.app.v1.MailboxListResponse
import ir.vmessenger.core.proto.app.v1.MailboxPut
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit mailbox protocol (Phase 8 / rc29).
 */
@Singleton
class MailboxProtocolService @Inject constructor(
    private val mailboxDao: MailboxDao,
    private val mailboxService: MailboxService,
) {
    suspend fun requestFetch(session: ActiveSecureSession, blobId: String) {
        if (!P2PConfig.storeAndForwardEnabled) return
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-fetch-$blobId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxFetch(
                ir.vmessenger.core.proto.app.v1.MailboxFetchRequest.newBuilder()
                    .setBlobId(ByteString.copyFromUtf8(blobId)),
            )
            .build()
        sendEnvelope(session, envelope)
    }

    suspend fun sendDeleteAck(session: ActiveSecureSession, blobId: String) {
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-del-$blobId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxDelete(
                MailboxDelete.newBuilder()
                    .setBlobId(ByteString.copyFromUtf8(blobId)),
            )
            .build()
        sendEnvelope(session, envelope)
    }

    suspend fun sendReply(session: ActiveSecureSession, reply: MessageEnvelope) {
        sendEnvelope(session, reply)
    }

    suspend fun handleIncoming(envelope: MessageEnvelope, session: ActiveSecureSession?): MessageEnvelope? {
        if (!P2PConfig.storeAndForwardEnabled) return null
        val reply = when {
            envelope.hasMailboxPut() -> handlePut(envelope)
            envelope.hasMailboxList() -> handleList(envelope)
            envelope.hasMailboxFetch() -> handleFetch(envelope)
            envelope.hasMailboxDelete() -> handleDelete(envelope)
            else -> null
        }
        if (reply != null && session != null) {
            sendReply(session, reply)
        }
        return reply
    }

    suspend fun requestList(session: ActiveSecureSession, self: PeerIdentity, recipientHash: ByteArray) {
        if (!P2PConfig.storeAndForwardEnabled) return
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-list-${System.currentTimeMillis()}"))
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxList(
                MailboxListRequest.newBuilder()
                    .setRecipientIdentityHash(ByteString.copyFrom(recipientHash)),
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

    private suspend fun handlePut(envelope: MessageEnvelope): MessageEnvelope? {
        val blob = envelope.mailboxPut.blob
        if (blob.sealedPayload.size() > MAX_BLOB_BYTES) return null
        mailboxService.storeIncoming(blob)
        return null
    }

    private suspend fun handleList(envelope: MessageEnvelope): MessageEnvelope {
        val hash = envelope.mailboxList.recipientIdentityHash.toByteArray()
        val now = System.currentTimeMillis()
        val ids = mailboxDao.forRecipient(hash, now).map { ByteString.copyFromUtf8(it.blobId) }
        return MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-list-resp-${System.currentTimeMillis()}"))
            .setSentAtUnixMs(now)
            .setCounter(1)
            .setMailboxListResponse(MailboxListResponse.newBuilder().addAllBlobIds(ids))
            .build()
    }

    private suspend fun handleFetch(envelope: MessageEnvelope): MessageEnvelope? {
        val blobId = envelope.mailboxFetch.blobId.toStringUtf8()
        val entry = mailboxDao.getById(blobId) ?: return null
        val blob = MailboxBlob.newBuilder()
            .setBlobId(ByteString.copyFromUtf8(entry.blobId))
            .setRecipientIdentityHash(ByteString.copyFrom(entry.recipientIdentityHash))
            .setSealedPayload(ByteString.copyFrom(entry.sealedPayload))
            .setExpiresAtUnixMs(entry.expiresAtUnixMs)
            .build()
        return MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-fetch-resp-$blobId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxFetchResponse(MailboxFetchResponse.newBuilder().setBlob(blob))
            .build()
    }

    private suspend fun handleDelete(envelope: MessageEnvelope): MessageEnvelope {
        val blobId = envelope.mailboxDelete.blobId.toStringUtf8()
        mailboxDao.delete(blobId)
        return MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("mailbox-del-ack-$blobId"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setMailboxDeleteAck(
                MailboxDeleteAck.newBuilder()
                    .setBlobId(envelope.mailboxDelete.blobId)
                    .setAccepted(true),
            )
            .build()
    }

    private suspend fun sendEnvelope(session: ActiveSecureSession, envelope: MessageEnvelope) {
        runCatching {
            val sealed = session.seal(envelope.toByteArray())
            val frame = Frame.newBuilder()
                .setVersion(1)
                .setType(FrameType.FRAME_TYPE_SECURE)
                .setBody(ByteString.copyFrom(sealed))
                .build()
            session.writeFrame(frame.toByteArray())
        }.onFailure {
            AppLogger.warn("MailboxProtocol", "send failed: ${it.message}")
        }
    }

    companion object {
        const val MAX_BLOB_BYTES = 256 * 1024
        const val MAX_BLOBS_PER_RECIPIENT = 50
    }
}
