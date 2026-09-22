package ir.vmessenger.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the device into a phone call and takes it back out again.
 *
 * Three things have to happen, and none of them is optional:
 *
 * `MODE_IN_COMMUNICATION` is what the echo canceller, the noise suppressor and the earpiece
 * routing are all conditioned on. Without it the capture effects attach and do nothing, the volume
 * keys change media volume instead of call volume, and a call played through a speaker feeds
 * straight back into its own microphone.
 *
 * Audio focus stops whatever was playing. A call held under someone's podcast is not a call.
 *
 * And the mode has to be *restored*, not just left — a process that exits `MODE_IN_COMMUNICATION`
 * by forgetting about it leaves the whole device routing audio as though a call were still up.
 */
@Singleton
class AudioSessionController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var previousMode: Int? = null

    /** Claims the device for a call. Safe to call twice; the second time does nothing. */
    fun open() {
        if (previousMode != null) return
        previousMode = manager.mode
        runCatching {
            requestFocus()
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
        }.onFailure { AppLogger.warn(TAG, "could not enter communication mode: ${it.message}") }
    }

    /**
     * Earpiece or speaker.
     *
     * From Android 12 this is a routing request to the platform rather than a switch we flip, which
     * is why a failure is logged and swallowed: a headset or a car is allowed to win, and a call
     * that ends because the user asked for the speaker would be a worse outcome than one that keeps
     * playing where it already was.
     */
    fun setSpeaker(on: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                routeCommunication(on)
            } else {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = on
            }
        }.onFailure {
            val destination = if (on) "speaker" else "earpiece"
            AppLogger.warn(TAG, "could not route audio to $destination: ${it.message}")
        }
    }

    /** Hands the device back exactly as it was found. */
    fun close() {
        val restore = previousMode ?: return
        previousMode = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.clearCommunicationDevice()
            manager.mode = restore
            focus?.let { manager.abandonAudioFocusRequest(it) }
        }.onFailure { AppLogger.warn(TAG, "could not leave communication mode: ${it.message}") }
        focus = null
    }

    private fun requestFocus() {
        // TRANSIENT_EXCLUSIVE: other apps should stop rather than duck. A call is not background
        // music to talk over, and a ducked podcast is still a podcast in the other ear.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focus = request
        manager.requestAudioFocus(request)
    }

    private fun routeCommunication(speaker: Boolean) {
        val wanted = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val device = manager.availableCommunicationDevices.firstOrNull { it.type == wanted }
        if (device == null) {
            AppLogger.info(TAG, "no communication device of type $wanted; leaving the platform's choice")
        } else {
            manager.setCommunicationDevice(device)
        }
    }

    private companion object {
        const val TAG = "Call"
    }
}
