package ir.vmessenger.feature.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure half of the recorder. The encoder itself needs a device, so it is exercised
 * by the two-emulator matrix instead.
 */
class VoiceRecorderTest {

    @Test
    fun `a waveform always has one bar per bucket`() {
        assertEquals(WAVEFORM_BUCKETS, waveformOf(emptyList()).size)
        assertEquals(WAVEFORM_BUCKETS, waveformOf(listOf(1, 2, 3)).size)
        assertEquals(WAVEFORM_BUCKETS, waveformOf(List(4_000) { it }).size)
    }

    @Test
    fun `silence draws nothing`() {
        assertTrue(waveformOf(List(200) { 0 }).all { it.toInt() == 0 })
    }

    @Test
    fun `the loudest sample of the recording reaches the top of the range`() {
        val bars = waveformOf(List(WAVEFORM_BUCKETS) { it })
        assertEquals(0, bars.first().level())
        assertEquals(255, bars.last().level())
    }

    @Test
    fun `a quiet recording is scaled up rather than left flat`() {
        // Scaling is relative to the loudest sample, so a whisper still fills the bars.
        val quiet = waveformOf(List(WAVEFORM_BUCKETS) { 100 })
        assertTrue(quiet.all { it.level() == 255 })
    }

    @Test
    fun `a bar is the mean of the samples that fall in it`() {
        val alternating = List(WAVEFORM_BUCKETS * 2) { index -> if (index % 2 == 0) 0 else 200 }
        assertTrue(waveformOf(alternating).all { it.level() == 127 })
    }

    @Test
    fun `fewer samples than buckets repeats them instead of leaving gaps`() {
        val bars = waveformOf(listOf(0, 255))
        assertEquals(0, bars.first().level())
        assertEquals(255, bars.last().level())
        assertTrue(bars.none { it.level() != 0 && it.level() != 255 })
    }

    @Test
    fun `the live level is a clamped fraction of the encoder range`() {
        assertEquals(0f, amplitudeLevel(0), TOLERANCE)
        assertEquals(0.5f, amplitudeLevel(16_384), 0.01f)
        assertEquals(1f, amplitudeLevel(32_767), TOLERANCE)
        assertEquals(1f, amplitudeLevel(Int.MAX_VALUE), TOLERANCE)
        assertEquals(0f, amplitudeLevel(-5), TOLERANCE)
    }

    private fun Byte.level(): Int = toInt() and 0xFF

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
