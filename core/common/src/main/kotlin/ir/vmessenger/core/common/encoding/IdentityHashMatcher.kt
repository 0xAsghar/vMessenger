package ir.vmessenger.core.common.encoding

object IdentityHashMatcher {
    /** Bytes of the identity hash encoded in a User Hash; routing must key on this prefix. */
    const val ROUTING_PREFIX_BYTES = 16

    fun matches(stored: ByteArray, candidate: ByteArray): Boolean {
        if (stored.contentEquals(candidate)) return true
        return matchesPartial(stored, candidate) || matchesPartial(candidate, stored)
    }

    /**
     * Normalized routing key for DHT/relay/cache lookups. User Hash pairing only
     * carries the first [ROUTING_PREFIX_BYTES] of the identity hash, so all
     * routing tables must key on the prefix for full and partial hashes to meet.
     */
    fun routingKeyHex(hash: ByteArray): String =
        hash.copyOf(ROUTING_PREFIX_BYTES).joinToString("") { "%02x".format(it) }

    /** Zero-padded 32-byte form of the routing prefix (for byte-keyed stores). */
    fun routingHash(hash: ByteArray): ByteArray =
        hash.copyOf(ROUTING_PREFIX_BYTES).copyOf(32)

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
