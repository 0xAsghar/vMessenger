package ir.vmessenger.node

import java.util.concurrent.ConcurrentHashMap

/**
 * Token-bucket rate limiter keyed by an arbitrary string (typically a client IP).
 *
 * Each key gets a bucket holding at most [burst] tokens that refills at
 * [ratePerMin] tokens per minute. [tryAcquire] takes one token when available.
 * Buckets untouched for [idleTtlMs] are dropped by [sweep], which the owner
 * should call from a periodic job so the map stays bounded by the number of
 * recently active keys.
 *
 * Thread-safe: the map is concurrent and every bucket mutation is synchronized
 * on the bucket itself.
 */
class RateLimiter(
    private val ratePerMin: Int,
    private val burst: Int,
    private val idleTtlMs: Long = DEFAULT_IDLE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(ratePerMin > 0) { "ratePerMin must be positive" }
        require(burst > 0) { "burst must be positive" }
    }

    private class Bucket(var tokens: Double, var updatedAtMs: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val tokensPerMs = ratePerMin / MS_PER_MINUTE

    /** Number of keys currently tracked (mainly for stats/tests). */
    val size: Int get() = buckets.size

    /** Returns true and consumes a token when [key] is under its limit. */
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val bucket = buckets.computeIfAbsent(key) { Bucket(burst.toDouble(), now) }
        synchronized(bucket) {
            val elapsed = (now - bucket.updatedAtMs).coerceAtLeast(0L)
            bucket.tokens = (bucket.tokens + elapsed * tokensPerMs).coerceAtMost(burst.toDouble())
            bucket.updatedAtMs = now
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    /** Drops buckets idle for longer than [idleTtlMs]; returns how many were removed. */
    fun sweep(): Int {
        val cutoff = clock() - idleTtlMs
        var removed = 0
        for ((key, bucket) in buckets) {
            val idle = synchronized(bucket) { bucket.updatedAtMs < cutoff }
            if (idle && buckets.remove(key, bucket)) removed++
        }
        return removed
    }

    companion object {
        const val DEFAULT_IDLE_TTL_MS = 10 * 60 * 1000L
        private const val MS_PER_MINUTE = 60_000.0
    }
}
