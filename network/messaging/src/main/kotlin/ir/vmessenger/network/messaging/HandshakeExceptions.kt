package ir.vmessenger.network.messaging

/** The peer speaks protocol major [peerMajor] instead of [ir.vmessenger.core.common.network.ProtocolVersion.MAJOR]. */
class ProtocolVersionException(
    val peerMajor: Int,
    message: String = "peer protocol major=$peerMajor unsupported",
) : IllegalStateException(message)

/**
 * The peer presented an X25519 static key different from the one pinned for
 * [identityHash]. The handshake is aborted; [newStaticKey] is surfaced so the
 * caller can record it as pending and ask the user to re-verify.
 */
class PeerKeyChangedException(
    val identityHash: ByteArray,
    val newStaticKey: ByteArray,
) : IllegalStateException("peer static key changed; re-verification required")
