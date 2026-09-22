package ir.vmessenger.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The buffer is where a call's timing actually lives, and every case here is one a real network
 * produces: frames swapped, duplicated, arriving after their turn, never arriving, or stopping.
 */
class JitterBufferTest {

    private val buffer = JitterBuffer(targetFrames = 3, maxFrames = 5)

    @Test
    fun `nothing plays until the buffer has filled to its target`() {
        buffer.offer(0, frame(1), SIZE)
        buffer.offer(1, frame(2), SIZE)

        // Two of three: concealing is right, because starting now means starving on the first gap.
        assertIs<JitterFrame.Conceal>(buffer.poll())
        assertFalse(buffer.started)

        buffer.offer(2, frame(3), SIZE)

        assertIs<JitterFrame.Packet>(buffer.poll())
        assertTrue(buffer.started)
    }

    @Test
    fun `frames that arrive swapped play in order`() {
        buffer.offer(2, frame(3), SIZE)
        buffer.offer(0, frame(1), SIZE)
        buffer.offer(1, frame(2), SIZE)

        assertEquals(listOf(1, 2, 3), List(3) { payloadTag(buffer.poll()) })
    }

    @Test
    fun `a lost frame is concealed and the stream steps over it`() {
        buffer.offer(0, frame(1), SIZE)
        buffer.offer(1, frame(2), SIZE)
        buffer.offer(3, frame(4), SIZE) // 2 never arrives

        assertEquals(1, payloadTag(buffer.poll()))
        assertEquals(2, payloadTag(buffer.poll()))
        // Sequence 2 is missing but 3 is waiting, so it is gone rather than late.
        assertIs<JitterFrame.Conceal>(buffer.poll())
        assertEquals(4, payloadTag(buffer.poll()))
    }

    @Test
    fun `a frame that arrives after its turn is dropped, not played late`() {
        buffer.offer(0, frame(1), SIZE)
        buffer.offer(1, frame(2), SIZE)
        buffer.offer(2, frame(3), SIZE)
        repeat(3) { buffer.poll() }

        buffer.offer(1, frame(99), SIZE)

        assertEquals(0, buffer.depth, "a frame from the past was kept")
    }

    @Test
    fun `a duplicate is ignored`() {
        buffer.offer(0, frame(1), SIZE)
        buffer.offer(0, frame(1), SIZE)

        assertEquals(1, buffer.depth)
    }

    @Test
    fun `the buffer refuses to grow past its cap, so jitter never becomes latency`() {
        repeat(9) { sequence -> buffer.offer(sequence, frame(sequence), SIZE) }

        assertEquals(5, buffer.depth)
        // The oldest went, so playback resumes at what is actually still held rather than
        // concealing its way through frames that were already discarded.
        assertEquals(4, payloadTag(buffer.poll()))
    }

    @Test
    fun `a sender that stops for long enough sends the buffer back to filling`() {
        buffer.offer(0, frame(1), SIZE)
        buffer.offer(1, frame(2), SIZE)
        buffer.offer(2, frame(3), SIZE)
        repeat(3) { buffer.poll() }
        assertTrue(buffer.started)

        repeat(JitterBuffer.STARVE_LIMIT) { buffer.poll() }

        assertFalse(buffer.started, "a stalled stream should refill rather than trickle concealment")
    }

    private fun frame(tag: Int) = ByteArray(SIZE) { tag.toByte() }

    private fun payloadTag(frame: JitterFrame): Int {
        assertIs<JitterFrame.Packet>(frame)
        return frame.payload.first().toInt()
    }

    private companion object {
        const val SIZE = 8
    }
}
