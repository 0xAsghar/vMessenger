package ir.vmessenger.network.dht

import java.util.ArrayDeque

/**
 * Sliding-window per-source limiter for embedded DHT STORE requests: at most
 * [maxPerWindow] accepted stores per source (client IP) per [windowMs]. Sources
 * are bounded so a flood of distinct addresses cannot grow memory unbounded.
 */
class StoreRateLimiter(
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxSources: Int = DEFAULT_MAX_SOURCES,
) {
    private val lock = Any()
    private val history = LinkedHashMap<String, ArrayDeque<Long>>()

    /** Records an attempt from [source] and returns whether it is within the limit. */
    fun allow(source: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val stamps = history.remove(source) ?: ArrayDeque()
        while (stamps.isNotEmpty() && nowMs - stamps.first() >= windowMs) stamps.removeFirst()
        val allowed = stamps.size < maxPerWindow
        if (allowed) stamps.addLast(nowMs)
        // Re-insert as most recently used; evict the least recently used source when full.
        history[source] = stamps
        while (history.size > maxSources) history.remove(history.keys.first())
        allowed
    }

    companion object {
        const val DEFAULT_MAX_PER_WINDOW = 10
        const val DEFAULT_WINDOW_MS = 60_000L
        const val DEFAULT_MAX_SOURCES = 1024
    }
}
