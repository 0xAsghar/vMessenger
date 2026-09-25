package ir.vmessenger.core.common.network

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * A certificate key pin: the SHA-256 of a DER `SubjectPublicKeyInfo`, written base64url without
 * padding (43 characters) — the same value as
 * `openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64url`.
 *
 * A pin names a key, not a certificate: a node that renews its certificate on the same key keeps
 * its pin.
 */
class SpkiPin private constructor(private val digest: ByteArray) {

    val text: String = BASE64URL.encodeToString(digest)

    /** Compared in constant time. */
    fun matchesSpki(spkiDer: ByteArray): Boolean = MessageDigest.isEqual(digest, sha256(spkiDer))

    fun matches(certificate: X509Certificate): Boolean = matchesSpki(certificate.publicKey.encoded)

    override fun equals(other: Any?): Boolean = other is SpkiPin && digest.contentEquals(other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String = "sha256/$text"

    companion object {
        private const val DIGEST_BYTES = 32
        private const val TEXT_LENGTH = 43
        private val BASE64URL = Base64.getUrlEncoder().withoutPadding()
        private val PIN_TEXT = Regex("^[A-Za-z0-9_-]{$TEXT_LENGTH}$")

        /** A pin written as [text], or null unless it is exactly 43 base64url characters. */
        fun parse(text: String): SpkiPin? {
            if (!PIN_TEXT.matches(text)) return null
            val bytes = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
            return bytes?.takeIf { it.size == DIGEST_BYTES }?.let(::SpkiPin)
        }

        fun of(certificate: X509Certificate): SpkiPin = ofSpki(certificate.publicKey.encoded)

        fun ofSpki(spkiDer: ByteArray): SpkiPin = SpkiPin(sha256(spkiDer))

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
