package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.proto.app.v1.MailboxBlob
import ir.vmessenger.core.proto.app.v1.MailboxDelete
import ir.vmessenger.core.proto.app.v1.MailboxFetchRequest
import ir.vmessenger.core.proto.app.v1.MailboxListRequest
import ir.vmessenger.core.proto.app.v1.MailboxPut
import ir.vmessenger.core.proto.app.v1.MessageEnvelope

/** Wire-shaped mailbox requests as a peer would send them (the in-memory `FakeMailboxDao` lives in CleanupFakes.kt). */
object MailboxFixtures {
    private const val ONE_HOUR_MS = 60 * 60 * 1000L

    fun blob(
        recipientHash: ByteArray,
        sealedPayload: ByteArray,
        blobId: String = "sender-chosen",
        sealVersion: Int = MailboxSeal.SEAL_VERSION,
        expiresAtUnixMs: Long = System.currentTimeMillis() + ONE_HOUR_MS,
    ): MailboxBlob = MailboxBlob.newBuilder()
        .setBlobId(ByteString.copyFromUtf8(blobId))
        .setRecipientIdentityHash(ByteString.copyFrom(recipientHash))
        .setSealedPayload(ByteString.copyFrom(sealedPayload))
        .setExpiresAtUnixMs(expiresAtUnixMs)
        .setSealVersion(sealVersion)
        .build()

    fun put(blob: MailboxBlob): MessageEnvelope = envelope("put")
        .setMailboxPut(MailboxPut.newBuilder().setBlob(blob))
        .build()

    fun list(recipientHash: ByteArray): MessageEnvelope = envelope("list")
        .setMailboxList(MailboxListRequest.newBuilder().setRecipientIdentityHash(ByteString.copyFrom(recipientHash)))
        .build()

    fun fetch(blobId: String): MessageEnvelope = envelope("fetch")
        .setMailboxFetch(MailboxFetchRequest.newBuilder().setBlobId(ByteString.copyFromUtf8(blobId)))
        .build()

    fun delete(blobId: String): MessageEnvelope = envelope("delete")
        .setMailboxDelete(MailboxDelete.newBuilder().setBlobId(ByteString.copyFromUtf8(blobId)))
        .build()

    private fun envelope(kind: String): MessageEnvelope.Builder = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("mailbox-$kind-${System.nanoTime()}"))
        .setSentAtUnixMs(System.currentTimeMillis())
        .setCounter(1)
}
