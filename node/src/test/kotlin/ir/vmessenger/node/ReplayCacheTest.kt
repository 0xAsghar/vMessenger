package ir.vmessenger.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayCacheTest {

    private val cache = ReplayCache(ttlMs = 1_000, maxEntries = 3)

    @Test
    fun `first sighting is accepted and the replay rejected`() {
        assertTrue(cache.checkAndRemember("k", 42, nowMs = 100))
        assertFalse(cache.checkAndRemember("k", 42, nowMs = 100))
        assertFalse(cache.checkAndRemember("k", 42, nowMs = 1_099))
    }

    @Test
    fun `same key with a different ts is a new proof`() {
        assertTrue(cache.checkAndRemember("k", 1, nowMs = 100))
        assertTrue(cache.checkAndRemember("k", 2, nowMs = 100))
        assertTrue(cache.checkAndRemember("other", 1, nowMs = 100))
    }

    @Test
    fun `expired entries are accepted again`() {
        assertTrue(cache.checkAndRemember("k", 42, nowMs = 100))
        assertFalse(cache.checkAndRemember("k", 42, nowMs = 1_099))
        assertTrue(cache.checkAndRemember("k", 42, nowMs = 1_100))
        // ...and are remembered afresh from the new sighting.
        assertFalse(cache.checkAndRemember("k", 42, nowMs = 2_000))
    }

    @Test
    fun `expired entries are evicted on insert`() {
        cache.checkAndRemember("a", 1, nowMs = 0)
        cache.checkAndRemember("b", 1, nowMs = 0)
        assertEquals(2, cache.size)
        cache.checkAndRemember("c", 1, nowMs = 1_000)
        assertEquals(1, cache.size)
    }

    @Test
    fun `cap evicts the oldest entry`() {
        assertTrue(cache.checkAndRemember("a", 1, nowMs = 0))
        assertTrue(cache.checkAndRemember("b", 1, nowMs = 1))
        assertTrue(cache.checkAndRemember("c", 1, nowMs = 2))
        assertEquals(3, cache.size)

        assertTrue(cache.checkAndRemember("d", 1, nowMs = 3))
        assertEquals(3, cache.size)
        // "a" was dropped to make room, so it no longer counts as a replay.
        assertTrue(cache.checkAndRemember("a", 1, nowMs = 4))
        // "c" and "d" are still remembered.
        assertFalse(cache.checkAndRemember("c", 1, nowMs = 4))
        assertFalse(cache.checkAndRemember("d", 1, nowMs = 4))
    }

    @Test
    fun `rejects non-positive parameters`() {
        assertTrue(runCatching { ReplayCache(ttlMs = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ReplayCache(ttlMs = 1, maxEntries = 0) }.exceptionOrNull() is IllegalArgumentException)
    }
}
