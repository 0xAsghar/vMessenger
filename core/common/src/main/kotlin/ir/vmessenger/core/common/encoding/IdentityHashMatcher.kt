package ir.vmessenger.core.common.encoding

object IdentityHashMatcher {
    fun matches(stored: ByteArray, candidate: ByteArray): Boolean {
        if (stored.contentEquals(candidate)) return true
        return matchesPartial(stored, candidate) || matchesPartial(candidate, stored)
    }

    fun isPlaceholderPublicKey(key: ByteArray): Boolean = key.all { it == 0.toByte() }

    fun hashPrefixHex(hash: ByteArray, bytes: Int = 4): String =
        hash.take(bytes).joinToString("") { "%02x".format(it) }

    private fun matchesPartial(prefixHolder: ByteArray, fullHolder: ByteArray): Boolean {
        if (prefixHolder.size < 16 || fullHolder.size < 32) return false
        val prefixMatch = prefixHolder.copyOf(16).contentEquals(fullHolder.copyOf(16))
        val suffixZero = prefixHolder.size >= 32 &&
            prefixHolder.copyOfRange(16, 32).all { it == 0.toByte() }
        return prefixMatch && suffixZero
    }
}
