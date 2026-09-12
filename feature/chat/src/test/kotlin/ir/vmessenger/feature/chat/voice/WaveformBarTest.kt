package ir.vmessenger.feature.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Test

private const val TOLERANCE = 0.001f

/** The drawing arithmetic of the waveform: what each bar is, and where a touch lands. */
class WaveformBarTest {

    @Test
    fun `a message without a waveform draws flat`() {
        assertEquals(0f, waveformLevel(null, 0), TOLERANCE)
        assertEquals(0f, waveformLevel(ByteArray(0), 10), TOLERANCE)
    }

    @Test
    fun `a waveform too short to say anything draws flat`() {
        assertEquals(0f, waveformLevel(ByteArray(4) { -1 }, 2), TOLERANCE)
    }

    @Test
    fun `bars are read as unsigned bytes`() {
        assertEquals(1f, waveformLevel(ByteArray(WAVEFORM_BUCKETS) { -1 }, 10), TOLERANCE)
        assertEquals(0f, waveformLevel(ByteArray(WAVEFORM_BUCKETS) { 0 }, 10), TOLERANCE)
    }

    @Test
    fun `a short waveform is stretched across the bars`() {
        val half = ByteArray(8) { index -> if (index < 4) 0 else -1 }
        assertEquals(0f, waveformLevel(half, 0), TOLERANCE)
        assertEquals(1f, waveformLevel(half, WAVEFORM_BUCKETS - 1), TOLERANCE)
    }

    @Test
    fun `a touch seeks to where it landed, clamped to the bar`() {
        assertEquals(0.5f, seekFraction(50f, 100f), TOLERANCE)
        assertEquals(1f, seekFraction(500f, 100f), TOLERANCE)
        assertEquals(0f, seekFraction(-20f, 100f), TOLERANCE)
    }

    @Test
    fun `a bar with no width seeks to the start rather than dividing by zero`() {
        assertEquals(0f, seekFraction(50f, 0f), TOLERANCE)
    }
}
