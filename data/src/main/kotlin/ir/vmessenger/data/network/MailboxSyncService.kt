package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls mailbox blobs from a connected peer after handshake (rc29/rc31).
 */
@Singleton
class MailboxSyncService @Inject constructor(
    private val mailboxProtocolService: MailboxProtocolService,
    private val mailboxService: MailboxService,
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
                mailboxService.storeIncoming(blob)
                session?.let {
                    mailboxProtocolService.sendDeleteAck(it, blob.blobId.toStringUtf8())
                }
                deliverLocalBlob(blob.sealedPayload.toByteArray())
            }
            else -> Unit
        }
    }

    private fun deliverLocalBlob(sealedPayload: ByteArray) {
        runCatching {
            val inner = MessageEnvelope.parseFrom(sealedPayload)
            if (inner.hasChat()) {
                AppLogger.info("Mailbox", "delivered deferred chat ${inner.messageId.toStringUtf8()}")
            }
        }.onFailure {
            AppLogger.warn("Mailbox", "blob delivery parse failed: ${it.message}")
        }
    }

    companion object {
        private const val MAX_FETCH_PER_SYNC = 10
    }
}
