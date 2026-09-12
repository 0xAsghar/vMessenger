package ir.vmessenger.feature.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val TOLERANCE = 0.001f

/**
 * The parts of playback that need no player: the speed cycle, the auto-advance order and the
 * position arithmetic.
 */
class VoicePlaybackControllerTest {

    @Test
    fun `the speed chip cycles one, one and a half, two`() {
        assertEquals(VoiceSpeed.FAST, VoiceSpeed.NORMAL.next())
        assertEquals(VoiceSpeed.FASTEST, VoiceSpeed.FAST.next())
        assertEquals(VoiceSpeed.NORMAL, VoiceSpeed.FASTEST.next())
    }

    @Test
    fun `each speed carries the factor the player is given`() {
        assertEquals(1f, VoiceSpeed.NORMAL.factor, TOLERANCE)
        assertEquals(1.5f, VoiceSpeed.FAST.factor, TOLERANCE)
        assertEquals(2f, VoiceSpeed.FASTEST.factor, TOLERANCE)
    }

    @Test
    fun `auto advance follows the order the caller gave`() {
        val queue = listOf("a", "b", "c")
        assertEquals("b", nextTrackId(queue, "a"))
        assertEquals("c", nextTrackId(queue, "b"))
        assertNull(nextTrackId(queue, "c"))
    }

    @Test
    fun `a queue that leaves out the finished message starts at its head`() {
        assertEquals("b", nextTrackId(listOf("b", "c"), "a"))
        assertEquals("b", nextTrackId(listOf("b", "c"), null))
    }

    @Test
    fun `an empty queue ends the run`() {
        assertNull(nextTrackId(emptyList(), "a"))
    }

    @Test
    fun `progress is a clamped fraction of the message`() {
        assertEquals(0f, progressOf(0, 1_000), TOLERANCE)
        assertEquals(0.5f, progressOf(500, 1_000), TOLERANCE)
        assertEquals(1f, progressOf(4_000, 1_000), TOLERANCE)
    }

    @Test
    fun `a duration the codec has not worked out yet reads as the start`() {
        assertEquals(0f, progressOf(500, 0), TOLERANCE)
        assertEquals(0f, progressOf(500, -1), TOLERANCE)
    }
}
