package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmTheme
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** How long a snackbar stays: a short confirmation, or long enough to read a sentence and act. */
enum class VmSnackbarDuration(val millis: Long) {
    Short(millis = 4_000),
    Long(millis = 8_000),
}

/** How a snackbar ended. */
enum class VmSnackbarResult { Dismissed, ActionPerformed }

/** One snackbar on screen, and the call waiting for it to end. */
@Stable
class VmSnackbarData internal constructor(
    val message: String,
    val actionLabel: String?,
    val duration: VmSnackbarDuration,
    private val continuation: CancellableContinuation<VmSnackbarResult>,
) {
    fun performAction() {
        if (continuation.isActive) continuation.resume(VmSnackbarResult.ActionPerformed)
    }

    fun dismiss() {
        if (continuation.isActive) continuation.resume(VmSnackbarResult.Dismissed)
    }
}

/**
 * Queues snackbars one at a time. [showSnackbar] suspends until the snackbar is gone and says
 * whether its action was taken, so a caller can wait on "Undo" the way it would on a dialog.
 */
@Stable
class VmSnackbarHostState {
    private val mutex = Mutex()

    var current: VmSnackbarData? by mutableStateOf(null)
        private set

    suspend fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        duration: VmSnackbarDuration = if (actionLabel == null) VmSnackbarDuration.Short else VmSnackbarDuration.Long,
    ): VmSnackbarResult = mutex.withLock {
        try {
            suspendCancellableCoroutine { continuation ->
                current = VmSnackbarData(message, actionLabel, duration, continuation)
            }
        } finally {
            current = null
        }
    }
}

/** Convenience factory so screens do not each re-declare the host state. */
@Composable
fun rememberVmSnackbar(): VmSnackbarHostState = remember { VmSnackbarHostState() }

/**
 * Where snackbars appear: a dark rounded bar, the message in the canvas colour, the action in the
 * accent. The inverse colours are what make it read as "about the app" rather than as content.
 *
 * Announced politely as a live region, so a screen reader speaks it without the user moving there.
 */
@Composable
fun VmSnackbarHost(
    hostState: VmSnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val data = hostState.current
    LaunchedEffect(data) {
        if (data != null) {
            delay(data.duration.millis)
            data.dismiss()
        }
    }
    // Remembered past its own dismissal so the exit animation still has something to draw.
    var shown by remember { mutableStateOf<VmSnackbarData?>(null) }
    if (data != null) shown = data
    AnimatedVisibility(
        visible = data != null,
        enter = slideInVertically(VmMotion.emphasis()) { it / 2 } + fadeIn(VmMotion.emphasis()),
        exit = fadeOut(VmMotion.fade()) + slideOutVertically(VmMotion.fade()) { it / 2 },
        modifier = modifier.navigationBarsPadding(),
    ) {
        shown?.let { current -> VmSnackbar(current) }
    }
}

@Composable
private fun VmSnackbar(data: VmSnackbarData) {
    val c = VmTheme.colors
    VmSurface(
        shape = VmShapes.snackbar,
        color = c.textPrimary,
        contentColor = c.bgCanvas,
        shadowElevation = VmElevation.sheet,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            VmText(
                text = data.message,
                style = VmTheme.typography.bodyMd,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            if (data.actionLabel != null) {
                VmSurface(
                    onClick = data::performAction,
                    shape = VmShapes.pill,
                    color = Color.Transparent,
                    contentColor = c.textAccentInverse,
                ) {
                    VmText(
                        text = data.actionLabel,
                        style = VmTheme.typography.bodyMdMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
