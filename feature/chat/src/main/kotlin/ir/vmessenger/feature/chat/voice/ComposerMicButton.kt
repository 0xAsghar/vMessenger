package ir.vmessenger.feature.chat.voice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.feature.chat.R
import kotlinx.coroutines.delay

/** Shorter than this the press was a tap on the mic, and taps do not record. */
private const val HOLD_TO_START_MS = 150L

private val CancelDistance = 120.dp
private val LockDistance = 80.dp

/** Where the mic gesture is. [Cancelled] is a moment the reducer reports, not a resting state. */
internal enum class MicPhase { Idle, Armed, Recording, Locked, Cancelled }

/** The two drag thresholds in pixels; the composable resolves them so the reducer stays pure. */
internal class MicLimits(val cancelPx: Float, val lockPx: Float)

/** What the button asks the screen to do; the recorder itself is none of its business. */
@Stable
internal class MicButtonActions(
    /** Opens the recorder. False when it may not start — no permission yet, or the mic is busy. */
    val onStart: () -> Boolean,
    val onCancel: () -> Unit,
    val onSend: () -> Unit,
    /** Hands-free from here on: the finger is gone but the recording continues. */
    val onLocked: () -> Unit,
    /** How far the finger is toward cancelling, 0..1, for the slide hint. */
    val onSlide: (Float) -> Unit,
)

/**
 * One pointer move, folded into the next phase. [towardField] is how far the finger has travelled
 * toward the text field and [upward] how far it has travelled up, both in pixels and both already
 * corrected for the layout direction.
 *
 * Cancelling is tested before locking, so a diagonal drag that reaches both thresholds throws the
 * recording away rather than committing to it. Only an unlocked recording reacts at all: a locked
 * one has no finger left to interpret, and a press that never started has nothing to cancel.
 */
internal fun reduceMicDrag(phase: MicPhase, towardField: Float, upward: Float, limits: MicLimits): MicPhase = when {
    phase != MicPhase.Recording -> phase
    towardField >= limits.cancelPx -> MicPhase.Cancelled
    upward >= limits.lockPx -> MicPhase.Locked
    else -> phase
}

/** How far the slide-to-cancel hint has travelled, 0..1. */
internal fun slideFraction(towardField: Float, cancelPx: Float): Float =
    if (cancelPx > 0f) (towardField / cancelPx).coerceIn(0f, 1f) else 0f

/**
 * The composer's mic: hold to record, slide toward the text field to cancel, drag up to lock.
 * Locked, it becomes the send button, because there is no longer a finger to lift.
 *
 * The phase lives here rather than in the screen so that the pointer loop, the back button and
 * the lifecycle all read the same one.
 */
@Composable
internal fun ComposerMicButton(
    actions: MicButtonActions,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val phase = remember { mutableStateOf(MicPhase.Idle) }
    val density = LocalDensity.current
    val limits = remember(density) { with(density) { MicLimits(CancelDistance.toPx(), LockDistance.toPx()) } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The callbacks are read through a State: a caller that rebuilds them every recomposition
    // must not restart the pointer loop, which would drop the gesture halfway through it.
    val current = rememberUpdatedState(actions)
    val gesture = remember(limits, rtl, phase) { MicGesture(current, limits, rtl, phase) }

    LaunchedEffect(phase.value) {
        if (phase.value == MicPhase.Armed) {
            delay(HOLD_TO_START_MS)
            gesture.begin()
        }
    }
    BackHandler(enabled = phase.value != MicPhase.Idle) { gesture.abandon() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { gesture.detach() }

    if (phase.value == MicPhase.Locked) {
        IconButton(onClick = gesture::send, modifier = modifier.size(VmSizes.touchTarget)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.feature_chat_voice_send),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = stringResource(R.string.feature_chat_voice_record),
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .micGestures(enabled, gesture)
                .size(VmSizes.touchTarget)
                .padding(VmSpacing.md),
        )
    }
}

/** The state machine behind the button, so the composable above stays a drawing. */
private class MicGesture(
    private val actions: State<MicButtonActions>,
    private val limits: MicLimits,
    private val rtl: Boolean,
    private val phase: MutableState<MicPhase>,
) {
    /** Pointer down. Nothing records yet — [begin] decides that once the press has held. */
    fun press() {
        phase.value = MicPhase.Armed
    }

    /** The hold elapsed; the recorder still gets the last word. */
    fun begin() {
        phase.value = if (actions.value.onStart()) MicPhase.Recording else MicPhase.Idle
    }

    /** True once the pointer stops mattering: the gesture has cancelled itself, or locked. */
    fun move(offset: Offset): Boolean {
        // The text field is left of the mic in LTR and right of it in RTL; up is negative y.
        val toward = if (rtl) offset.x else -offset.x
        val next = reduceMicDrag(phase.value, toward, -offset.y, limits)
        actions.value.onSlide(slideFraction(toward, limits.cancelPx))
        return when (next) {
            MicPhase.Cancelled -> {
                abandon()
                true
            }
            MicPhase.Locked -> {
                phase.value = MicPhase.Locked
                actions.value.onSlide(0f)
                actions.value.onLocked()
                true
            }
            else -> {
                phase.value = next
                false
            }
        }
    }

    /** Pointer up: a held recording is sent, a locked one carries on, a tap leaves no trace. */
    fun release() {
        when (phase.value) {
            MicPhase.Recording -> send()
            MicPhase.Locked -> Unit
            else -> reset()
        }
    }

    fun send() {
        if (recording()) actions.value.onSend()
        reset()
    }

    fun abandon() {
        if (recording()) actions.value.onCancel()
        reset()
    }

    /** Backgrounded: a held recording has lost the finger holding it; a locked one was meant. */
    fun detach() {
        if (phase.value == MicPhase.Locked) send() else abandon()
    }

    private fun recording(): Boolean = phase.value == MicPhase.Recording || phase.value == MicPhase.Locked

    private fun reset() {
        phase.value = MicPhase.Idle
        actions.value.onSlide(0f)
    }
}

/** The raw press — down, drag, up. What any of it means is [MicGesture]'s business. */
private fun Modifier.micGestures(enabled: Boolean, gesture: MicGesture): Modifier =
    pointerInput(enabled, gesture) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            gesture.press()
            var settled = false
            while (!settled) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                settled = when {
                    change == null || !change.pressed -> true
                    else -> gesture.move(change.position - down.position)
                }
            }
            gesture.release()
        }
    }
