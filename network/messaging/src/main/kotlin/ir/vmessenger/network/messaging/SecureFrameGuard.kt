package ir.vmessenger.network.messaging

import com.google.protobuf.InvalidProtocolBufferException
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.ProtocolVersion
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SecureFrameOutcome {
    /** A decrypted envelope; [sessionExpired] asks the caller to drop the session after handling it. */
    data class Envelope(val envelope: MessageEnvelope, val sessionExpired: Boolean) : SecureFrameOutcome

    /** The session was closed by the guard (version mismatch or peer CLOSE). */
    data class Closed(val reason: String) : SecureFrameOutcome

    /** The frame was dropped; the session stays up. */
    data object Ignored : SecureFrameOutcome
}

/**
 * Validates every post-handshake frame before any key material is touched:
 * `Frame.version == 2` (a mismatch closes the session — no per-frame
 * tolerance), `FRAME_TYPE_CLOSE` is honoured, counters must be `>= 1`, and the
 * ratchet bounds skips. No exception escapes [process]; malformed input is
 * logged and dropped.
 */
@Singleton
class SecureFrameGuard @Inject constructor() {
    @Suppress("ReturnCount") // early exits keep the guard flat and auditable
    suspend fun process(session: ActiveSecureSession, contactId: String, frameBytes: ByteArray): SecureFrameOutcome {
        // Transports may still drain buffered frames after close; a wiped ratchet
        // must never be consulted for them, so they are dropped before parsing.
        if (session.isClosed) {
            AppLogger.debug("Messaging", "inbound frame after close dropped contact=$contactId")
            return SecureFrameOutcome.Closed("session closed")
        }
        val frame = try {
            Frame.parseFrom(frameBytes)
        } catch (e: InvalidProtocolBufferException) {
            AppLogger.warn("Messaging", "inbound frame parse failed contact=$contactId: ${e.message}")
            return SecureFrameOutcome.Ignored
        }
        if (frame.version != ProtocolVersion.MAJOR) {
            AppLogger.warn(
                "Messaging",
                "inbound frame version=${frame.version} (need ${ProtocolVersion.MAJOR}) contact=$contactId; closing",
            )
            session.close()
            return SecureFrameOutcome.Closed("protocol version mismatch")
        }
        when (frame.type) {
            FrameType.FRAME_TYPE_CLOSE -> {
                val close = CloseFrames.decode(frame)
                AppLogger.info("Messaging", "peer closed session contact=$contactId ${CloseFrames.describe(close)}")
                session.close()
                return SecureFrameOutcome.Closed("peer close code=${close.code}")
            }
            FrameType.FRAME_TYPE_SECURE -> Unit
            else -> {
                AppLogger.debug("Messaging", "inbound skip frame type=${frame.type} contact=$contactId")
                return SecureFrameOutcome.Ignored
            }
        }
        if (frame.counter <= 0) {
            AppLogger.warn("Messaging", "inbound frame counter=${frame.counter} rejected contact=$contactId")
            return SecureFrameOutcome.Ignored
        }
        val plaintext = session.open(frame.body.toByteArray(), frame.counter, frame.type)
        if (plaintext == null) {
            AppLogger.warn("Messaging", "inbound decrypt failed contact=$contactId counter=${frame.counter}")
            return SecureFrameOutcome.Ignored
        }
        val envelope = try {
            MessageEnvelope.parseFrom(plaintext)
        } catch (e: InvalidProtocolBufferException) {
            AppLogger.warn("Messaging", "inbound envelope parse failed contact=$contactId: ${e.message}")
            return SecureFrameOutcome.Ignored
        }
        val expired = session.isExpired()
        if (expired) {
            AppLogger.info(
                "Messaging",
                "session expired contact=$contactId frames=${session.frameCount}; re-handshake required",
            )
        }
        return SecureFrameOutcome.Envelope(envelope, sessionExpired = expired)
    }
}
