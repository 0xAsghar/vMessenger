package ir.vmessenger.data.call

import ir.vmessenger.core.crypto.CryptoEngine

/**
 * Names for a call's relay circuits.
 *
 * A relay passes the circuit id a dialer chooses through to the listener verbatim, so a name is all
 * it takes for the callee's relay listener to hand a circuit to the call rather than to the
 * messaging handshake. The name comes from the call's media key, which only the two ends hold:
 * nobody else can name a circuit into the call, and the relay, which does see the name, learns no
 * more from it than that a circuit was opened.
 */
internal object CallCircuits {
    private const val PREFIX = "vmcall-"
    private const val INFO = "vmessenger-call-circuit-v1"
    private const val TAG_BYTES = 16

    /** What every circuit of the call keyed by [key] starts with; the callee claims exactly this. */
    fun prefix(crypto: CryptoEngine, key: ByteArray): String {
        val tag = crypto.hkdfSha256(ikm = key, salt = ByteArray(0), info = INFO.toByteArray(), length = TAG_BYTES)
        return PREFIX + tag.joinToString("") { "%02x".format(it) } + "-"
    }

    /** Each attempt its own circuit: a relay refuses a second dial under a name still pending. */
    fun id(prefix: String, attempt: Int): String = prefix + attempt
}
