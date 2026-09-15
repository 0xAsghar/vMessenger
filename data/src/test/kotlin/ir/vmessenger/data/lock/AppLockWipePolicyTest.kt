package ir.vmessenger.data.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wait between wrong PINs.
 *
 * Argon2id already costs a second or so a guess, but the wipe that would *bound* the guessing is
 * opt-in and off by default, so without a wait the shipped default left an unattended phone to be
 * walked through the whole four-digit keyspace.
 */
class AppLockWipePolicyTest {
    @Test
    fun `the first few wrong PINs cost nothing but the guess`() {
        for (attempt in 0 until AppLockWipePolicy.BACKOFF_AFTER_ATTEMPTS) {
            assertEquals("attempt $attempt", 0L, AppLockWipePolicy.backoffMs(attempt))
        }
    }

    @Test
    fun `the wait starts on the threshold and doubles`() {
        val first = AppLockWipePolicy.backoffMs(AppLockWipePolicy.BACKOFF_AFTER_ATTEMPTS)
        val second = AppLockWipePolicy.backoffMs(AppLockWipePolicy.BACKOFF_AFTER_ATTEMPTS + 1)
        val third = AppLockWipePolicy.backoffMs(AppLockWipePolicy.BACKOFF_AFTER_ATTEMPTS + 2)

        assertEquals(AppLockWipePolicy.BACKOFF_BASE_MS, first)
        assertEquals(first * 2, second)
        assertEquals(second * 2, third)
    }

    @Test
    fun `the wait caps rather than growing without bound`() {
        assertEquals(AppLockWipePolicy.BACKOFF_MAX_MS, AppLockWipePolicy.backoffMs(40))
        // The shift would overflow long before this if it were not clamped first.
        assertEquals(AppLockWipePolicy.BACKOFF_MAX_MS, AppLockWipePolicy.backoffMs(Int.MAX_VALUE))
    }

    @Test
    fun `the wipe threshold is far enough out that the wait bites first`() {
        assertTrue(
            "a wipe before any wait would make the wait pointless",
            AppLockWipePolicy.BACKOFF_AFTER_ATTEMPTS < AppLockWipePolicy.MAX_FAILED_ATTEMPTS,
        )
    }
}
