package ir.vmessenger.feature.lock

import ir.vmessenger.core.crypto.lock.PinVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinEntryTest {
    @Test
    fun submittableOnlyAtTheVerifiersMinimum() {
        val entry = PinEntry()
        repeat(PinVerifier.MIN_PIN_LENGTH - 1) { entry.append('1') }
        assertFalse(entry.isSubmittable)
        entry.append('1')
        assertTrue(entry.isSubmittable)
    }

    @Test
    fun takeHandsOutTheDigitsAndKeepsNone() {
        val entry = PinEntry()
        "1234".forEach(entry::append)
        assertArrayEquals(charArrayOf('1', '2', '3', '4'), entry.take())
        assertEquals(0, entry.length)
        // Whatever was typed is gone from the buffer, not merely out of reach behind a length.
        assertArrayEquals(charArrayOf(), entry.take())
    }

    @Test
    fun backspaceStopsAtEmptyAndAppendStopsAtTheMaximum() {
        val entry = PinEntry()
        entry.backspace()
        assertEquals(0, entry.length)
        repeat(PinVerifier.MAX_PIN_LENGTH + 4) { entry.append('7') }
        assertEquals(PinVerifier.MAX_PIN_LENGTH, entry.length)
    }
}
