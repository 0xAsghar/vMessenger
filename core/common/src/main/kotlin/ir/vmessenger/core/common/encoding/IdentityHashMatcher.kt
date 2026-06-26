package ir.vmessenger.core.common.encoding

object IdentityHashMatcher {
    fun matches(stored: ByteArray, candidate: ByteArray): Boolean {
        if (stored.contentEquals(candidate)) return true
        if (stored.size < 16 || candidate.size < 32) return false
        val prefixMatch = stored.copyOf(16).contentEquals(candidate.copyOf(16))
        val storedSuffixZero = stored.size >= 32 &&
            stored.copyOfRange(16, 32).all { it == 0.toByte() }
        return prefixMatch && storedSuffixZero
    }

    fun isPlaceholderPublicKey(key: ByteArray): Boolean = key.all { it == 0.toByte() }
}
