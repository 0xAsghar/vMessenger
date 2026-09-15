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

    @Test
    fun `the wait runs down when both clocks agree it has`() {
        val left = AppLockWipePolicy.remainingWaitMs(
            owedMs = 10_000L,
            lastWallMs = 1_000L,
            nowWallMs = 7_000L,
            lastElapsedMs = 500L,
            nowElapsedMs = 6_500L,
        )
        assertEquals(4_000L, left)
    }

    /** The one the person holding the phone would try first. */
    @Test
    fun `winding the device clock forward does not shorten the wait`() {
        val left = AppLockWipePolicy.remainingWaitMs(
            owedMs = 60_000L,
            lastWallMs = 1_000L,
            // A year later, allegedly, against five seconds of actual uptime.
            nowWallMs = 999_999_000L,
            lastElapsedMs = 500L,
            nowElapsedMs = 5_500L,
        )
        assertEquals(55_000L, left)
    }

    /**
     * After a reboot the stored uptime is in the future, so it cannot be differenced. Falling back
     * to the wall clock is the honest answer: a reboot costs more time than most waits anyway.
     */
    @Test
    fun `a reboot falls back to the wall clock instead of forgiving the wait`() {
        val left = AppLockWipePolicy.remainingWaitMs(
            owedMs = 60_000L,
            lastWallMs = 1_000L,
            nowWallMs = 21_000L,
            lastElapsedMs = 900_000L,
            nowElapsedMs = 4_000L,
        )
        assertEquals(40_000L, left)
    }

    @Test
    fun `an expired wait is zero, never negative`() {
        val left = AppLockWipePolicy.remainingWaitMs(
            owedMs = 5_000L,
            lastWallMs = 0L,
            nowWallMs = 90_000L,
            lastElapsedMs = 0L,
            nowElapsedMs = 90_000L,
        )
        assertEquals(0L, left)
    }

    /**
     * The case the first version of this function got wrong, and the case its tests did not have.
     *
     * A backward wall clock makes the debt look larger than it ever was, and nothing decays it:
     * the too-soon branch runs before the PIN is checked, so the right PIN is never tested and the
     * stamp is never cleared. Clamping the number bounded what the screen printed and left the
     * user locked out just the same; the clock that moved has to be discarded instead.
     */
    @Test
    fun `a wall clock that moved backwards is discarded, not trusted`() {
        val left = AppLockWipePolicy.remainingWaitMs(
            owedMs = 60_000L,
            // The RTC reset to 1970: the stamp is decades in the future by this clock.
            lastWallMs = 1_700_000_000_000L,
            nowWallMs = 1_000L,
            lastElapsedMs = 500L,
            nowElapsedMs = 90_500L,
        )
        assertEquals(0L, left)
    }

    @Test
    fun `with both clocks unusable there is no debt to serve`() {
        val left = AppLockWipePolicy.remainingWaitMs(
            owedMs = 60_000L,
            lastWallMs = 1_700_000_000_000L,
            nowWallMs = 1_000L,
            lastElapsedMs = 900_000L,
            nowElapsedMs = 4_000L,
        )
        assertEquals(0L, left)
    }

    /** A backward wall clock must not rescue someone the monotonic clock still owes time. */
    @Test
    fun `the surviving clock still holds the line`() {
        val left = AppLockWipePolicy.remainingWaitMs(
            owedMs = 60_000L,
            lastWallMs = 1_700_000_000_000L,
            nowWallMs = 1_000L,
            lastElapsedMs = 500L,
            nowElapsedMs = 20_500L,
        )
        assertEquals(40_000L, left)
    }
}
