package ir.vmessenger.core.proto

import com.google.protobuf.ByteString
import ir.vmessenger.core.proto.app.v1.AttachmentInfo
import ir.vmessenger.core.proto.app.v1.AttachmentKind
import ir.vmessenger.core.proto.app.v1.CallSignal
import ir.vmessenger.core.proto.app.v1.CallSignalType
import ir.vmessenger.core.proto.app.v1.ChatMessage
import ir.vmessenger.core.proto.app.v1.GpsBuzzerRequest
import ir.vmessenger.core.proto.app.v1.GroupControl
import ir.vmessenger.core.proto.app.v1.GroupControlType
import ir.vmessenger.core.proto.app.v1.GroupMember
import ir.vmessenger.core.proto.app.v1.GroupMemberRole
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ir.vmessenger.core.proto.v112.app.v1.AttachmentInfo as V112AttachmentInfo
import ir.vmessenger.core.proto.v112.app.v1.AttachmentKind as V112AttachmentKind
import ir.vmessenger.core.proto.v112.app.v1.ChatMessage as V112ChatMessage
import ir.vmessenger.core.proto.v112.app.v1.GroupControl as V112GroupControl
import ir.vmessenger.core.proto.v112.app.v1.GroupControlType as V112GroupControlType
import ir.vmessenger.core.proto.v112.app.v1.GroupMember as V112GroupMember
import ir.vmessenger.core.proto.v112.app.v1.MessageEnvelope as V112Envelope
import ir.vmessenger.core.proto.v112.app.v1.Receipt as V112Receipt
import ir.vmessenger.core.proto.v112.app.v1.ReceiptType as V112ReceiptType

/**
 * Bytes, both ways, between 2.0 and a phone still on 1.1.2 — using 1.1.2's own schema, compiled here
 * under another package (`src/test/proto/v112`), rather than anyone's reading of it.
 *
 * Only `messaging.proto` changed since 1.1.2; every other schema file is identical, which
 * [V1WireCompatibilityTest] holds.
 */
class V1WireRoundTripTest {
    @Test
    fun `a message from 1_1_2 reads the same here, and untimed`() {
        val sent = V112Envelope.newBuilder()
            .setMessageId(bytes("m1"))
            .setSenderIdentityHash(ByteString.copyFrom(ByteArray(32) { 3 }))
            .setSentAtUnixMs(SENT_AT)
            .setCounter(9)
            .setChat(V112ChatMessage.newBuilder().setText("سلام").setReplyToMessageId(bytes("m0")))
            .build()

        val read = MessageEnvelope.parseFrom(sent.toByteArray())

        assertEquals(MessageEnvelope.ContentCase.CHAT, read.contentCase)
        assertEquals("سلام", read.chat.text)
        assertEquals("m0", read.chat.replyToMessageId.toStringUtf8())
        assertEquals(SENT_AT, read.sentAtUnixMs)
        assertEquals(9L, read.counter)
        assertEquals("a 1.1.2 message never self-destructs", 0L, read.expiresAtUnixMs)
    }

    @Test
    fun `a file, a receipt and a group from 1_1_2 arrive intact, as a standalone file and a group without roles`() {
        val image = V112Envelope.newBuilder().setAttachmentInfo(
            V112AttachmentInfo.newBuilder()
                .setTransferId(bytes("t1"))
                .setKind(V112AttachmentKind.ATTACHMENT_KIND_IMAGE),
        ).build()
        val receipt = V112Envelope.newBuilder().setReceipt(
            V112Receipt.newBuilder().setRefMessageId(bytes("m1")).setType(V112ReceiptType.RECEIPT_TYPE_READ),
        ).build()
        val group = V112Envelope.newBuilder().setGroupControl(
            V112GroupControl.newBuilder()
                .setType(V112GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT)
                .setVersion(7)
                .addMembers(V112GroupMember.newBuilder().setDisplayName("Bob")),
        ).build()

        val info = MessageEnvelope.parseFrom(image.toByteArray()).attachmentInfo
        assertEquals("t1", info.transferId.toStringUtf8())
        assertTrue("not part of any album", info.albumId.isEmpty)
        assertEquals("m1", MessageEnvelope.parseFrom(receipt.toByteArray()).receipt.refMessageId.toStringUtf8())
        val snapshot = MessageEnvelope.parseFrom(group.toByteArray()).groupControl
        assertEquals(7L, snapshot.version)
        assertEquals("Bob", snapshot.membersList.single().displayName)
        assertEquals(GroupMemberRole.GROUP_MEMBER_ROLE_UNSPECIFIED, snapshot.membersList.single().role)
        assertFalse("a 1.1.2 group never keeps an audit trail", snapshot.auditRetention)
    }

    @Test
    fun `an album image, and a group with roles, reach 1_1_2 as the image and the group it knows`() {
        val image = MessageEnvelope.newBuilder().setAttachmentInfo(
            AttachmentInfo.newBuilder()
                .setTransferId(bytes("t2"))
                .setKind(AttachmentKind.ATTACHMENT_KIND_IMAGE)
                .setAlbumId(bytes("album"))
                .setAlbumIndex(2)
                .setAlbumCount(4),
        ).build()
        val group = MessageEnvelope.newBuilder().setGroupControl(
            GroupControl.newBuilder()
                .setType(GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT)
                .setVersion(8)
                .setAuditRetention(true)
                .addMembers(
                    GroupMember.newBuilder().setDisplayName("Ann").setRole(GroupMemberRole.GROUP_MEMBER_ROLE_ADMIN),
                ),
        ).build()

        val info = V112Envelope.parseFrom(image.toByteArray()).attachmentInfo
        assertEquals("t2", info.transferId.toStringUtf8())
        assertEquals(V112AttachmentKind.ATTACHMENT_KIND_IMAGE, info.kind)
        val snapshot = V112Envelope.parseFrom(group.toByteArray()).groupControl
        assertEquals(8L, snapshot.version)
        assertEquals("Ann", snapshot.membersList.single().displayName)
    }

    @Test
    fun `a role change is a group control 1_1_2 cannot mistake for one it knows`() {
        val promote = MessageEnvelope.newBuilder().setGroupControl(
            GroupControl.newBuilder().setType(GroupControlType.GROUP_CONTROL_TYPE_SET_ROLE).setVersion(9),
        ).build()

        val read = V112Envelope.parseFrom(promote.toByteArray()).groupControl

        assertEquals(V112GroupControlType.UNRECOGNIZED, read.type)
    }

    @Test
    fun `a timed message reaches 1_1_2 as an ordinary one, not as an error`() {
        val sent = MessageEnvelope.newBuilder()
            .setMessageId(bytes("m2"))
            .setExpiresAtUnixMs(SENT_AT + 3_600_000)
            .setChat(ChatMessage.newBuilder().setText("timed"))
            .build()

        val read = V112Envelope.parseFrom(sent.toByteArray())

        assertEquals(V112Envelope.ContentCase.CHAT, read.contentCase)
        assertEquals("timed", read.chat.text)
    }

    @Test
    fun `a call or a location request is nothing at all to 1_1_2, so it is dropped rather than misread`() {
        val call = MessageEnvelope.newBuilder()
            .setMessageId(bytes("c1"))
            .setCallSignal(CallSignal.newBuilder().setType(CallSignalType.CALL_SIGNAL_TYPE_INVITE))
            .build()
        val buzz = MessageEnvelope.newBuilder()
            .setMessageId(bytes("b1"))
            .setGpsBuzzer(GpsBuzzerRequest.newBuilder().setAtUnixMs(SENT_AT))
            .build()

        assertEquals(V112Envelope.ContentCase.CONTENT_NOT_SET, V112Envelope.parseFrom(call.toByteArray()).contentCase)
        assertEquals(V112Envelope.ContentCase.CONTENT_NOT_SET, V112Envelope.parseFrom(buzz.toByteArray()).contentCase)
        // The envelope itself still parses: the id is there for a receipt, if 1.1.2 wanted to send one.
        assertEquals("c1", V112Envelope.parseFrom(call.toByteArray()).messageId.toStringUtf8())
    }

    private fun bytes(text: String): ByteString = ByteString.copyFromUtf8(text)

    private companion object {
        const val SENT_AT = 1_750_000_000_000L
    }
}
