package ir.vmessenger.data.call

import ir.vmessenger.core.audio.CallAudio
import ir.vmessenger.core.audio.OpusCodec
import kotlinx.coroutines.flow.Flow

/**
 * The device side of a call's audio — microphone, speaker, codec, and the platform's call mode —
 * behind an interface so [CallMediaSession], where the decisions are, can run under test.
 */
internal interface MediaDevices {
    /** 20 ms frames from the microphone, for as long as they are collected. */
    fun capture(): Flow<ShortArray>

    fun codec(): OpusCodec

    /** Communication mode, audio focus and routing: on at the first path, off at the call's end. */
    fun openSession()

    fun closeSession()

    fun openPlayback()

    /** Plays one frame, returning when there is room for the next: this is what paces playback. */
    suspend fun play(pcm: ShortArray, samples: Int)

    fun closePlayback()
}

/** The real devices. */
internal class CallAudioDevices(private val audio: CallAudio) : MediaDevices {
    override fun capture(): Flow<ShortArray> = audio.capture.frames()

    override fun codec(): OpusCodec = audio.codecs.create()

    override fun openSession() = audio.session.open()

    override fun closeSession() = audio.session.close()

    override fun openPlayback() = audio.playback.open()

    // Blocking by design, on the media dispatcher: AudioTrack.write returns once the frame fits.
    override suspend fun play(pcm: ShortArray, samples: Int) = audio.playback.write(pcm, samples)

    override fun closePlayback() = audio.playback.close()
}
