package ir.vmessenger.network.messaging

import ir.vmessenger.core.crypto.CryptoEngine
import javax.inject.Inject
import javax.inject.Singleton

data class RatchetState(
    var sendChainKey: ByteArray,
    var recvChainKey: ByteArray,
    var sendCounter: Long = 0,
    var recvCounter: Long = 0,
    val seenCounters: MutableSet<Long> = mutableSetOf(),
) {
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

@Singleton
class SymmetricRatchet @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    fun initFromRoot(rootKey: ByteArray, isInitiator: Boolean): RatchetState {
        val sendKey = cryptoEngine.hkdfSha256(rootKey, ByteArray(0), "send".toByteArray(), 32)
        val recvKey = cryptoEngine.hkdfSha256(rootKey, ByteArray(0), "recv".toByteArray(), 32)
        return if (isInitiator) {
            RatchetState(sendChainKey = sendKey, recvChainKey = recvKey)
        } else {
            RatchetState(sendChainKey = recvKey, recvChainKey = sendKey)
        }
    }

    fun seal(state: RatchetState, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        state.sendCounter += 1
        val messageKey = cryptoEngine.hkdfSha256(
            state.sendChainKey,
            ByteArray(0),
            state.sendCounter.toString().toByteArray(),
            32,
        )
        state.sendChainKey = cryptoEngine.hkdfSha256(
            state.sendChainKey,
            ByteArray(0),
            "chain".toByteArray(),
            32,
        )
        return cryptoEngine.seal(plaintext, messageKey, associatedData)
    }

    fun open(state: RatchetState, ciphertext: ByteArray, counter: Long, associatedData: ByteArray): ByteArray? {
        if (counter <= 0 || counter in state.seenCounters) return null
        var chainKey = state.recvChainKey
        repeat((counter - 1).toInt()) {
            chainKey = cryptoEngine.hkdfSha256(chainKey, ByteArray(0), "chain".toByteArray(), 32)
        }
        val messageKey = cryptoEngine.hkdfSha256(
            chainKey,
            ByteArray(0),
            counter.toString().toByteArray(),
            32,
        )
        if (counter > state.recvCounter) {
            state.recvChainKey = cryptoEngine.hkdfSha256(chainKey, ByteArray(0), "chain".toByteArray(), 32)
            state.recvCounter = counter
        }
        state.seenCounters.add(counter)
        return cryptoEngine.open(ciphertext, messageKey, associatedData)
    }
}
