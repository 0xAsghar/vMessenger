package ir.vmessenger.feature.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Test

private val Limits = MicLimits(cancelPx = 120f, lockPx = 80f)
private const val TOLERANCE = 0.001f

/** The mic gesture's thresholds, without a finger. */
class ComposerMicButtonTest {

    @Test
    fun `a press that never started has nothing to cancel`() {
        assertEquals(MicPhase.Idle, reduceMicDrag(MicPhase.Idle, 999f, 999f, Limits))
        assertEquals(MicPhase.Armed, reduceMicDrag(MicPhase.Armed, 999f, 999f, Limits))
    }

    @Test
    fun `a locked recording no longer listens to the finger`() {
        assertEquals(MicPhase.Locked, reduceMicDrag(MicPhase.Locked, 999f, 999f, Limits))
    }

    @Test
    fun `cancelling needs the whole slide`() {
        assertEquals(MicPhase.Recording, reduceMicDrag(MicPhase.Recording, 119f, 0f, Limits))
        assertEquals(MicPhase.Cancelled, reduceMicDrag(MicPhase.Recording, 120f, 0f, Limits))
    }

    @Test
    fun `locking needs the whole lift`() {
        assertEquals(MicPhase.Recording, reduceMicDrag(MicPhase.Recording, 0f, 79f, Limits))
        assertEquals(MicPhase.Locked, reduceMicDrag(MicPhase.Recording, 0f, 80f, Limits))
    }

    @Test
    fun `a diagonal drag that reaches both thresholds cancels`() {
        assertEquals(MicPhase.Cancelled, reduceMicDrag(MicPhase.Recording, 200f, 200f, Limits))
    }

    @Test
    fun `dragging away from the text field or downwards changes nothing`() {
        assertEquals(MicPhase.Recording, reduceMicDrag(MicPhase.Recording, -400f, -400f, Limits))
    }

    @Test
    fun `the hint follows the finger to the cancel threshold and no further`() {
        assertEquals(0f, slideFraction(0f, Limits.cancelPx), TOLERANCE)
        assertEquals(0.5f, slideFraction(60f, Limits.cancelPx), TOLERANCE)
        assertEquals(1f, slideFraction(400f, Limits.cancelPx), TOLERANCE)
        assertEquals(0f, slideFraction(-40f, Limits.cancelPx), TOLERANCE)
    }

    @Test
    fun `a zero threshold does not divide by zero`() {
        assertEquals(0f, slideFraction(40f, 0f), TOLERANCE)
    }

    /**
     * The mic is the composer row's leading child, so in RTL it is drawn on the right and the
     * text field is to its left. These four assertions are the whole reason the sign is not
     * inlined: the app ships RTL-only, so an inverted axis would cancel on a drag away from the
     * field and nothing would ever contradict it.
     */
    @Test
    fun `in RTL the field is to the left of the mic, so a leftward drag is toward it`() {
        assertEquals(60f, towardField(dx = -60f, rtl = true), TOLERANCE)
        assertEquals(-60f, towardField(dx = 60f, rtl = true), TOLERANCE)
    }

    @Test
    fun `in LTR it is the other way round`() {
        assertEquals(60f, towardField(dx = 60f, rtl = false), TOLERANCE)
        assertEquals(-60f, towardField(dx = -60f, rtl = false), TOLERANCE)
    }

    @Test
    fun `a full leftward drag in RTL cancels, the same drag rightward does not`() {
        val left = towardField(dx = -200f, rtl = true)
        val right = towardField(dx = 200f, rtl = true)
        assertEquals(MicPhase.Cancelled, reduceMicDrag(MicPhase.Recording, left, 0f, Limits))
        assertEquals(MicPhase.Recording, reduceMicDrag(MicPhase.Recording, right, 0f, Limits))
    }
}
