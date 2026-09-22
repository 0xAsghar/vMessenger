package ir.vmessenger.core.audio

/** A frame handed back by [JitterBuffer.poll], or [Conceal] when playback must invent one. */
sealed interface JitterFrame {
    data class Packet(val payload: ByteArray, val length: Int) : JitterFrame {
        override fun equals(other: Any?): Boolean =
            other is Packet && length == other.length && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * payload.contentHashCode() + length
    }

    /** Nothing to play: a lost frame, or the buffer still filling. Opus conceals it. */
    data object Conceal : JitterFrame
}

/**
 * Holds arriving audio frames just long enough to play them in order at a steady rate.
 *
 * A call's frames arrive early, late, out of order, twice, or not at all, while playback consumes
 * exactly one every 20 ms. This absorbs that difference and nothing else — it is pure bookkeeping
 * over sequence numbers, which is why it is unit-tested while the engines around it cannot be.
 *
 * Three rules do most of the work. It waits for [targetFrames] before starting, so the first gap
 * does not immediately starve it. It never holds more than [maxFrames], because a buffer that grows
 * to absorb a slow link converts jitter into latency, and a call with a second of delay is worse
 * than a call with a gap. And a frame that arrives after its turn has passed is dropped rather than
 * played late, since playing it would put the stream further behind than the loss did.
 */
class JitterBuffer(
    private val targetFrames: Int = DEFAULT_TARGET_FRAMES,
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
) {
    private val frames = HashMap<Int, JitterFrame.Packet>()
    private var nextSequence = 0
    private var playing = false
    private var starvedPolls = 0

    val depth: Int get() = frames.size

    /** True once enough has arrived to start playing; false while filling or after a stall. */
    val started: Boolean get() = playing

    fun offer(sequence: Int, payload: ByteArray, length: Int) {
        // Its turn has passed: playing it now would leave the stream further behind than the gap it
        // was meant to fill.
        if (playing && sequence < nextSequence) return
        if (frames.containsKey(sequence)) return
        frames[sequence] = JitterFrame.Packet(payload.copyOf(length), length)
        if (frames.size > maxFrames) dropOldest()
    }

    fun poll(): JitterFrame {
        if (!playing) return fill()
        val frame = frames.remove(nextSequence)
        return if (frame == null) {
            concealMissing()
        } else {
            nextSequence++
            starvedPolls = 0
            frame
        }
    }

    /** Back to filling; used when a call's media path is re-established. */
    fun reset() {
        frames.clear()
        nextSequence = 0
        playing = false
        starvedPolls = 0
    }

    /** Waiting for [targetFrames] before the first frame plays, so one gap does not starve us. */
    private fun fill(): JitterFrame {
        if (frames.size < targetFrames) return JitterFrame.Conceal
        playing = true
        nextSequence = frames.keys.min()
        return poll()
    }

    /**
     * The expected frame is not here. If later frames are waiting it is lost, so the stream steps
     * over it. If nothing is waiting the sender has gone quiet — DTX, or the path is down — and the
     * position is held rather than raced forward, until a long enough silence warrants refilling.
     */
    private fun concealMissing(): JitterFrame {
        if (frames.isNotEmpty()) {
            nextSequence++
            return JitterFrame.Conceal
        }
        starvedPolls++
        if (starvedPolls >= STARVE_LIMIT) reset()
        return JitterFrame.Conceal
    }

    private fun dropOldest() {
        val oldest = frames.keys.min()
        frames.remove(oldest)
        // Never behind the window we still hold, or every later poll conceals a frame we dropped.
        if (playing && nextSequence <= oldest) nextSequence = oldest + 1
    }

    companion object {
        /** ~60 ms of slack: enough for ordinary jitter, short enough not to be heard as delay. */
        const val DEFAULT_TARGET_FRAMES = 3

        /** ~240 ms. Past this, holding more frames is just latency. */
        const val DEFAULT_MAX_FRAMES = 12

        /** Half a second of nothing arriving; treat the stream as interrupted and refill. */
        const val STARVE_LIMIT = 25
    }
}
