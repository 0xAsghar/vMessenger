package ir.vmessenger.core.audio

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything a call needs from the device's audio, in one place.
 *
 * A grouping rather than a layer: the four parts are used together for the whole of a call and
 * never apart, so passing them as one keeps the media path's dependencies about the media path.
 */
@Singleton
class CallAudio @Inject constructor(
    val capture: AudioCaptureEngine,
    val playback: AudioPlaybackEngine,
    val codecs: OpusCodecFactory,
    val session: AudioSessionController,
)
