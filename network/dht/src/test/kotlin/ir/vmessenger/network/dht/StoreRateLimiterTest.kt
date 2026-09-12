package ir.vmessenger.network.dht

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreRateLimiterTest {
    @Test
    fun tenPerMinutePerSource() {
        val limiter = StoreRateLimiter()
        repeat(StoreRateLimiter.DEFAULT_MAX_PER_WINDOW) { assertTrue(limiter.allow("10.0.0.1", nowMs = 1_000L + it)) }
        assertFalse(limiter.allow("10.0.0.1", nowMs = 2_000L))
        // Another source has its own budget.
        assertTrue(limiter.allow("10.0.0.2", nowMs = 2_000L))
        // Once the oldest attempt leaves the window a new one is admitted.
        val later = 1_000L + StoreRateLimiter.DEFAULT_WINDOW_MS
        assertTrue(limiter.allow("10.0.0.1", nowMs = later))
        // ...but the window is full again right away (nine old stamps plus the new one).
        assertFalse(limiter.allow("10.0.0.1", nowMs = later))
    }

    @Test
    fun sourceTableIsBounded() {
        val limiter = StoreRateLimiter(maxPerWindow = 1, maxSources = 2)
        assertTrue(limiter.allow("a", nowMs = 1))
        assertTrue(limiter.allow("b", nowMs = 1))
        assertTrue(limiter.allow("c", nowMs = 1)) // evicts "a"
        assertTrue(limiter.allow("a", nowMs = 1)) // fresh budget again, evicts "b"
        assertFalse(limiter.allow("c", nowMs = 1))
    }
}
