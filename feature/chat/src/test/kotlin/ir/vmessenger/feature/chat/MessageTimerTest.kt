package ir.vmessenger.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTimerTest {
    @Test
    fun `a duration gives every message its own lifetime`() {
        val timer = MessageTimer.After(durationMs = 3_600_000)

        assertEquals(NOW + 3_600_000, timer.deadlineFor(NOW))
        assertEquals(NOW + 60_000 + 3_600_000, timer.deadlineFor(NOW + 60_000))
    }

    @Test
    fun `a date gives every message the same moment, however late it is sent`() {
        val timer = MessageTimer.At(atUnixMs = NOW + 7_200_000)

        assertEquals(NOW + 7_200_000, timer.deadlineFor(NOW))
        assertEquals(NOW + 7_200_000, timer.deadlineFor(NOW + 7_000_000))
    }

    @Test
    fun `a date that has come has nothing left to give`() {
        val timer = MessageTimer.At(atUnixMs = NOW)

        assertNull(timer.deadlineFor(NOW))
        assertNull(timer.deadlineFor(NOW + 1))
    }

    private companion object {
        const val NOW = 1_750_000_000_000L
    }
}
