package ir.vmessenger.node

/**
 * Remembers `(key, ts)` pairs for [ttlMs] so a captured signed proof cannot be
 * presented twice within its validity window.
 *
 * Entries expire [ttlMs] after they were remembered; expired entries are
 * evicted lazily on insert. The cache never grows past [maxEntries]: when full,
 * the oldest remembered pair is dropped (which only ever weakens replay
 * protection for the oldest, soonest-to-expire proofs).
 *
 * Thread-safe via a single lock; every operation is O(1) amortised.
 */
class ReplayCache(
    private val ttlMs: Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(ttlMs > 0) { "ttlMs must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private data class Entry(val key: String, val ts: Long)

    private val lock = Any()

    /** Insertion-ordered so the eldest entry (and, for a monotone clock, the soonest to expire) is first. */
    private val seen = LinkedHashMap<Entry, Long>()

    /** Number of remembered pairs, including any that expired but were not yet evicted. */
    val size: Int get() = synchronized(lock) { seen.size }

    /**
     * Returns true and remembers `(key, ts)` when the pair is new (or its
     * previous sighting has expired); false when it was already seen within
     * the window, i.e. the proof is a replay.
     */
    fun checkAndRemember(key: String, ts: Long, nowMs: Long): Boolean {
        val entry = Entry(key, ts)
        synchronized(lock) {
            evictExpired(nowMs)
            val expiresAt = seen[entry]
            if (expiresAt != null && expiresAt > nowMs) return false
            seen.remove(entry)
            if (seen.size >= maxEntries) evictOldest()
            seen[entry] = nowMs + ttlMs
            return true
        }
    }

    private fun evictExpired(nowMs: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value > nowMs) break
            iterator.remove()
        }
    }

    private fun evictOldest() {
        val iterator = seen.entries.iterator()
        if (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 200_000
    }
}
