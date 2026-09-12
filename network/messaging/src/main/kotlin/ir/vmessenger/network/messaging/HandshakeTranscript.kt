package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.network.Canonical
import ir.vmessenger.core.proto.wire.v1.Capabilities
import ir.vmessenger.core.proto.wire.v1.HandshakeMessage
import java.security.MessageDigest

/**
 * Handshake v2 transcript (docs/Security.md §6):
 *
 * ```
 * canon(step) = u32be(step) || lp(ephemeral_pub) || lp(static_pub) || lp(identity_pub) || lp(caps) || lp(payload)
 * caps        = u32be(protocol_major) || u32be(protocol_minor) || u32be(n) || lpUtf8(feature_i)...
 * T1 = TAG || canon(step1)
 * T2 = T1  || canon(step2)          (signature field is never part of canon)
 * T3 = T2  || canon(step3)
 * sig2 = Ed25519(sk_R, SIG_TAG_RESPONDER || SHA256(T2))
 * sig3 = Ed25519(sk_I, SIG_TAG_INITIATOR || SHA256(T3))
 * ```
 *
 * Both sides feed the *received* message through [canonical], so protobuf
 * serialization determinism is irrelevant, and every key each party
 * contributes is covered by that party's own signature.
 */
object HandshakeTranscript {
    const val TAG = "vmessenger-hs-v2"
    const val SIG_TAG_RESPONDER = "vmessenger-hs-v2-sig-responder"
    const val SIG_TAG_INITIATOR = "vmessenger-hs-v2-sig-initiator"
    const val ROOT_INFO = "vmessenger-hs-v2-root"
    const val I2R_INFO = "vmessenger-hs-v2-i2r"
    const val R2I_INFO = "vmessenger-hs-v2-r2i"

    enum class Role { INITIATOR, RESPONDER }

    fun canonical(step: HandshakeMessage): ByteArray =
        Canonical.u32be(step.step) +
            Canonical.lp(step.ephemeralPub.toByteArray()) +
            Canonical.lp(step.staticPub.toByteArray()) +
            Canonical.lp(step.identityPub.toByteArray()) +
            Canonical.lp(capabilitiesBytes(step.capabilities)) +
            Canonical.lp(step.payload.toByteArray())

    fun capabilitiesBytes(caps: Capabilities): ByteArray {
        var out = Canonical.u32be(caps.protocolMajor) +
            Canonical.u32be(caps.protocolMinor) +
            Canonical.u32be(caps.featuresCount)
        for (feature in caps.featuresList) {
            out += Canonical.lpUtf8(feature)
        }
        return out
    }

    fun t1(step1: HandshakeMessage): ByteArray = TAG.toByteArray(Charsets.UTF_8) + canonical(step1)

    fun extend(previous: ByteArray, step: HandshakeMessage): ByteArray = previous + canonical(step)

    /** Bytes actually signed / verified for [role] over [transcript]. */
    fun signatureInput(role: Role, transcript: ByteArray): ByteArray {
        val tag = when (role) {
            Role.INITIATOR -> SIG_TAG_INITIATOR
            Role.RESPONDER -> SIG_TAG_RESPONDER
        }
        return tag.toByteArray(Charsets.UTF_8) + sha256(transcript)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
