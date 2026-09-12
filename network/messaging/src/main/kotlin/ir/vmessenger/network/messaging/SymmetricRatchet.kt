package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Canonical
import ir.vmessenger.core.crypto.CryptoEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-session symmetric ratchet state. Both chains advance: the send chain on
 * every seal, the receive chain on every successfully opened frame (receive-side
 * forward secrecy). Keys for frames that arrived out of order live in [skipped]
 * until used, bounded by [SymmetricRatchet.MAX_SKIPPED_STORE].
 */
class RatchetState(
    var sendChainKey: ByteArray,
    var recvChainKey: ByteArray,
    var sendCounter: Long = 0,
    /** Highest counter whose key has been derived into the chain (consumed or skipped). */
    var recvCounter: Long = 0,
    val skipped: LinkedHashMap<Long, ByteArray> = LinkedHashMap(),
) {
    /**
     * Set by [wipe]. A wiped state holds all-zero chain keys, and the message
     * keys derived from those are publicly computable, so [SymmetricRatchet]
     * refuses to seal or open under it.
     */
    @Volatile
    var wiped: Boolean = false
        private set

    /** Zeroizes every key this state holds. The state is unusable afterwards. */
    fun wipe() {
        wiped = true
        sendChainKey.fill(0)
        recvChainKey.fill(0)
        skipped.values.forEach { it.fill(0) }
        skipped.clear()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RatchetState
        return sendChainKey.contentEquals(other.sendChainKey) &&
            recvChainKey.contentEquals(other.recvChainKey) &&
            sendCounter == other.sendCounter &&
            recvCounter == other.recvCounter
    }

    override fun hashCode(): Int {
        var result = sendChainKey.contentHashCode()
        result = 31 * result + recvChainKey.contentHashCode()
        result = 31 * result + sendCounter.hashCode()
        result = 31 * result + recvCounter.hashCode()
        return result
    }
}

/**
 * Symmetric-key ratchet (protocol v2):
 *
 * ```
 * mk_n = HKDF(ck_{n-1}, salt=∅, info = "vmsg-v2-mk" || u64be(n))
 * ck_n = HKDF(ck_{n-1}, salt=∅, info = "vmsg-v2-ck")
 * AD   = associatedData || u64be(n)
 * ```
 *
 * `open` rejects counters more than [MAX_SKIP] ahead *before* deriving anything
 * (no CPU amplification), derives into temporaries, and commits state only after
 * the AEAD succeeds, so a forged frame never advances or poisons the chain.
 */
@Singleton
class SymmetricRatchet @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    fun initFromRoot(rootKey: ByteArray, isInitiator: Boolean): RatchetState {
        val i2r = cryptoEngine.hkdfSha256(rootKey, ByteArray(0), HandshakeTranscript.I2R_INFO.toByteArray(), KEY_BYTES)
        val r2i = cryptoEngine.hkdfSha256(rootKey, ByteArray(0), HandshakeTranscript.R2I_INFO.toByteArray(), KEY_BYTES)
        return if (isInitiator) {
            RatchetState(sendChainKey = i2r, recvChainKey = r2i)
        } else {
            RatchetState(sendChainKey = r2i, recvChainKey = i2r)
        }
    }

    /** Seals under the next send counter; read `state.sendCounter` afterwards for the frame header. */
    fun seal(state: RatchetState, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        check(!state.wiped) { "ratchet state wiped" }
        val counter = state.sendCounter + 1
        val messageKey = messageKey(state.sendChainKey, counter)
        val nextChain = chainStep(state.sendChainKey)
        cryptoEngine.memzero(state.sendChainKey)
        state.sendChainKey = nextChain
        state.sendCounter = counter
        val sealed = cryptoEngine.seal(plaintext, messageKey, associatedData + Canonical.u64be(counter))
        cryptoEngine.memzero(messageKey)
        return sealed
    }

    fun open(state: RatchetState, ciphertext: ByteArray, counter: Long, associatedData: ByteArray): ByteArray? {
        if (state.wiped || counter <= 0) return null
        val fullAd = associatedData + Canonical.u64be(counter)
        if (counter <= state.recvCounter) {
            return openSkipped(state, ciphertext, counter, fullAd)
        }
        if (counter > state.recvCounter + MAX_SKIP) {
            AppLogger.warn("Ratchet", "ratchet gap too large counter=$counter recv=${state.recvCounter}")
            return null
        }
        val derived = deriveForward(state, counter)
        val plaintext = cryptoEngine.open(ciphertext, derived.messageKey, fullAd)
        cryptoEngine.memzero(derived.messageKey)
        if (plaintext == null) {
            derived.skipped.forEach { (_, key) -> cryptoEngine.memzero(key) }
            cryptoEngine.memzero(derived.nextChainKey)
            return null
        }
        commit(state, derived, counter)
        return plaintext
    }

    private fun openSkipped(state: RatchetState, ciphertext: ByteArray, counter: Long, fullAd: ByteArray): ByteArray? {
        val key = state.skipped[counter] ?: return null
        val plaintext = cryptoEngine.open(ciphertext, key, fullAd)
        if (plaintext != null) {
            state.skipped.remove(counter)
            cryptoEngine.memzero(key)
        }
        return plaintext
    }

    private class Derived(
        val skipped: List<Pair<Long, ByteArray>>,
        val messageKey: ByteArray,
        val nextChainKey: ByteArray,
    )

    /** Derives keys for `recvCounter+1 .. counter` into temporaries; [state] is untouched. */
    private fun deriveForward(state: RatchetState, counter: Long): Derived {
        var chainKey = state.recvChainKey
        val skipped = ArrayList<Pair<Long, ByteArray>>()
        var n = state.recvCounter + 1
        while (n < counter) {
            skipped += n to messageKey(chainKey, n)
            chainKey = advance(chainKey, state.recvChainKey)
            n++
        }
        val messageKey = messageKey(chainKey, counter)
        val nextChainKey = advance(chainKey, state.recvChainKey)
        return Derived(skipped, messageKey, nextChainKey)
    }

    private fun advance(chainKey: ByteArray, stateChainKey: ByteArray): ByteArray {
        val next = chainStep(chainKey)
        if (chainKey !== stateChainKey) cryptoEngine.memzero(chainKey)
        return next
    }

    private fun commit(state: RatchetState, derived: Derived, counter: Long) {
        for ((n, key) in derived.skipped) {
            state.skipped[n] = key
        }
        while (state.skipped.size > MAX_SKIPPED_STORE) {
            val oldest = state.skipped.keys.first()
            state.skipped.remove(oldest)?.let { cryptoEngine.memzero(it) }
        }
        cryptoEngine.memzero(state.recvChainKey)
        state.recvChainKey = derived.nextChainKey
        state.recvCounter = counter
    }

    private fun messageKey(chainKey: ByteArray, counter: Long): ByteArray =
        cryptoEngine.hkdfSha256(chainKey, ByteArray(0), MK_INFO + Canonical.u64be(counter), KEY_BYTES)

    private fun chainStep(chainKey: ByteArray): ByteArray =
        cryptoEngine.hkdfSha256(chainKey, ByteArray(0), CK_INFO, KEY_BYTES)

    companion object {
        /** Frames further ahead than this are dropped before any KDF work. */
        const val MAX_SKIP = 256L

        /** Oldest skipped keys are evicted (and wiped) beyond this many. */
        const val MAX_SKIPPED_STORE = 256

        private const val KEY_BYTES = 32
        private val MK_INFO = "vmsg-v2-mk".toByteArray(Charsets.UTF_8)
        private val CK_INFO = "vmsg-v2-ck".toByteArray(Charsets.UTF_8)
    }
}
