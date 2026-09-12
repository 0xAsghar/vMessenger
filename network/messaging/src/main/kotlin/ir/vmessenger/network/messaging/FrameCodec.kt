package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.network.Canonical
import ir.vmessenger.core.common.network.ProtocolVersion
import ir.vmessenger.core.proto.wire.v1.Close
import ir.vmessenger.core.proto.wire.v1.CloseCode
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType

/**
 * AEAD associated data for a secure frame:
 *
 * `"vmsg-frame-v2" || u32be(2) || u32be(frameType) || SHA256(sender_ed25519_pub) || u64be(counter)`
 *
 * The ratchet appends `u64be(counter)`; this object builds the prefix. Binding
 * the version, frame type and counter means none of the frame header can be
 * altered in transit without failing authentication.
 */
object FrameAssociatedData {
    private val TAG = "vmsg-frame-v2".toByteArray(Charsets.UTF_8)

    fun prefix(frameType: FrameType, senderPublicKeyHash: ByteArray): ByteArray =
        TAG +
            Canonical.u32be(ProtocolVersion.MAJOR) +
            Canonical.u32be(frameType.number) +
            senderPublicKeyHash
}

/** Encodes the unencrypted `CLOSE` notice sent before dropping a connection. */
object CloseFrames {
    /** Upper bound on a peer-supplied close message we are willing to log. */
    private const val MAX_LOGGED_MESSAGE = 64

    fun encode(code: CloseCode, message: String): ByteArray =
        Frame.newBuilder()
            .setVersion(ProtocolVersion.MAJOR)
            .setType(FrameType.FRAME_TYPE_CLOSE)
            .setBody(
                Close.newBuilder()
                    .setCode(code.number)
                    .setMessage(message)
                    .setSupportedMajor(ProtocolVersion.MAJOR)
                    .build()
                    .toByteString(),
            )
            .build()
            .toByteArray()

    /** Parses a close body leniently; a malformed body yields an UNSPECIFIED close. */
    fun decode(frame: Frame): Close =
        runCatching { Close.parseFrom(frame.body) }.getOrDefault(Close.getDefaultInstance())

    fun describe(close: Close): String =
        "code=${close.code} supported_major=${close.supportedMajor} " +
            "message=${close.message.take(MAX_LOGGED_MESSAGE)}"
}
