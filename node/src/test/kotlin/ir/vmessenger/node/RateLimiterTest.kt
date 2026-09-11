package ir.vmessenger.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    private var now = 1_000_000L
    private val limiter = RateLimiter(ratePerMin = 60, burst = 3, clock = { now })

    @Test
    fun `burst is granted then refused`() {
        repeat(3) { assertTrue("acquire #$it", limiter.tryAcquire("a")) }
        assertFalse(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
    }

    @Test
    fun `tokens refill at the configured rate`() {
        repeat(3) { limiter.tryAcquire("a") }
        assertFalse(limiter.tryAcquire("a"))

        now += 999 // 60/min = 1 token per second; not quite there yet
        assertFalse(limiter.tryAcquire("a"))

        now += 1
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))

        now += 2_000
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
    }

    @Test
    fun `refill never exceeds the burst`() {
        repeat(3) { limiter.tryAcquire("a") }
        now += 60_000
        repeat(3) { assertTrue(limiter.tryAcquire("a")) }
        assertFalse(limiter.tryAcquire("a"))
    }

    @Test
    fun `keys are isolated`() {
        repeat(3) { limiter.tryAcquire("a") }
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
    }

    @Test
    fun `sweep drops idle buckets only`() {
        limiter.tryAcquire("old")
        now += 5 * 60_000
        limiter.tryAcquire("fresh")
        assertEquals(2, limiter.size)

        now += 5 * 60_000 + 1 // "old" idle for 10 min + 1 ms, "fresh" for 5 min
        assertEquals(1, limiter.sweep())
        assertEquals(1, limiter.size)

        // A swept key starts over with a full burst.
        repeat(3) { assertTrue(limiter.tryAcquire("old")) }
        assertFalse(limiter.tryAcquire("old"))
    }

    @Test
    fun `clock going backwards does not mint tokens`() {
        repeat(3) { limiter.tryAcquire("a") }
        now -= 60_000
        assertFalse(limiter.tryAcquire("a"))
    }
}
