package ir.vmessenger.feature.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.component.VmProgressIndicator
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.data.lock.AppLockWipePolicy
import ir.vmessenger.data.lock.LockState

private val LockIconSize = 32.dp
private val LockTileSize = 64.dp

/** The wait is reported in whole seconds, rounded up, so it never reads as zero while it lasts. */
private const val MILLIS_PER_SECOND = 1_000L

/** The keypad against the screen edges would be a row of keys that are hard to miss by accident. */
private const val KEYPAD_WIDTH_FRACTION = 0.88f

/**
 * The lock, drawn over the whole app rather than navigated to.
 *
 * Everything here assumes it is the topmost layer: it is opaque, it swallows the back gesture, and
 * it takes every touch that lands on it, so none reaches the screen behind — which is still
 * composed during the brief cover before the timeout decides. There is no NavController in reach
 * and there should not be — this is not a destination anyone can leave.
 *
 * [onUnlocked] fires once the coordinator says the app is open, including the case where there
 * turns out to be no PIN stored at all; a gate with no key behind it must not strand anyone.
 */
@Composable
fun AppLockScreen(onUnlocked: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: AppLockViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.unlocked) {
        if (state.unlocked) onUnlocked()
    }
    BackHandler(enabled = true) {
        // Deliberately nothing. Back is how a dialog is dismissed, and this is not a dialog.
    }
    VmSurface(
        color = VmTheme.colors.bgCanvas,
        // An empty handler still claims the touch: the hit stops here instead of reaching the
        // screen underneath.
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {},
    ) {
        AppLockContent(
            state = state,
            onUnlock = viewModel::unlock,
            onBiometricResult = viewModel::onBiometricResult,
        )
    }
}

@Composable
private fun AppLockContent(
    state: AppLockUiState,
    onUnlock: (CharArray) -> Unit,
    onBiometricResult: (Boolean) -> Unit,
) {
    val entry = remember { PinEntry() }
    DisposableEffect(entry) {
        // Leaving the lock behind with the digits still in memory would undo the point of it.
        onDispose { entry.clear() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(VmSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.lg),
    ) {
        Spacer(modifier = Modifier.weight(1f))
        LockHeader(lockState = state.lockState)
        PinDots(length = entry.length)
        StatusLine(state = state)
        Spacer(modifier = Modifier.weight(1f))
        PinKeypad(
            entry = entry,
            enabled = !state.checking,
            modifier = Modifier.fillMaxWidth(KEYPAD_WIDTH_FRACTION),
            onSubmit = { onUnlock(entry.take()) },
        )
        BiometricAction(state = state, onResult = onBiometricResult)
    }
}

@Composable
private fun LockHeader(lockState: LockState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        VmSurface(shape = VmShapes.card, color = VmTheme.colors.bgSubtle) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(LockTileSize)) {
                VmIcon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = VmTheme.colors.iconPrimary,
                    size = LockIconSize,
                )
            }
        }
        VmText(
            text = stringResource(R.string.app_lock_title),
            style = VmTheme.typography.headingMd,
            color = VmTheme.colors.textPrimary,
        )
        ModeChip(strict = lockState == LockState.LockedStrict)
    }
}

/**
 * Which of the two locks this is, in words.
 *
 * Not decoration. In strict mode nothing is being delivered while this screen is up, and a user
 * who is not told that reads the silence as an app that has stopped working.
 */
@Composable
private fun ModeChip(strict: Boolean) {
    VmSurface(
        color = VmTheme.colors.bgSubtle,
        contentColor = VmTheme.colors.textSecondary,
        shape = VmShapes.pill,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
            modifier = Modifier.padding(horizontal = VmSpacing.md, vertical = VmSpacing.xs),
        ) {
            VmIcon(
                imageVector = if (strict) Icons.Filled.CloudOff else Icons.Filled.CloudDone,
                contentDescription = null,
                size = VmSizes.iconMd,
            )
            VmText(
                text = stringResource(
                    if (strict) R.string.app_lock_mode_strict else R.string.app_lock_mode_soft,
                ),
                style = VmTheme.typography.bodySm,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One slot for whatever the screen currently owes the user: progress, a failure, or the ask. */
@Composable
private fun StatusLine(state: AppLockUiState) {
    when {
        state.checking -> CheckingRow()
        state.feedback != null -> FeedbackText(feedback = state.feedback, wipeArmed = state.wipeArmed)
        else -> VmText(
            text = stringResource(R.string.app_lock_prompt),
            style = VmTheme.typography.bodyMd,
            color = VmTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun CheckingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        VmProgressIndicator(size = VmSizes.iconMd, color = VmTheme.colors.iconSecondary)
        VmText(
            text = stringResource(R.string.app_lock_checking),
            style = VmTheme.typography.bodyMd,
            color = VmTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun FeedbackText(feedback: UnlockFeedback, wipeArmed: Boolean) {
    val text = when (feedback) {
        is UnlockFeedback.Wrong -> wrongText(attempt = feedback.attempt, wipeArmed = wipeArmed)
        is UnlockFeedback.TooSoon -> stringResource(
            R.string.app_lock_too_soon,
            VmTextFormat.digits(((feedback.waitMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toString()),
        )
        UnlockFeedback.HardwareRefused -> stringResource(R.string.app_lock_hardware_refused)
        UnlockFeedback.BiometricDone -> stringResource(R.string.app_lock_biometric_done)
        UnlockFeedback.BiometricFailed -> stringResource(R.string.app_lock_biometric_failed)
    }
    VmText(
        text = text,
        style = VmTheme.typography.bodyMd,
        color = if (feedback is UnlockFeedback.BiometricDone) {
            VmTheme.colors.textSuccess
        } else {
            VmTheme.colors.textCritical
        },
        textAlign = TextAlign.Center,
    )
}

/**
 * The count is the coordinator's running total, so it keeps its meaning across a force-stop.
 *
 * With the wipe trigger armed the wording has to be blunt about what the next few guesses cost:
 * finding out afterwards that the app erased itself is finding out too late.
 */
@Composable
private fun wrongText(attempt: Int, wipeArmed: Boolean): String {
    val remaining = (AppLockWipePolicy.MAX_FAILED_ATTEMPTS - attempt).coerceAtLeast(0)
    return when {
        !wipeArmed -> stringResource(R.string.app_lock_wrong, persian(attempt))
        remaining <= AppLockWipePolicy.WARN_AT_REMAINING ->
            stringResource(R.string.app_lock_wrong_wipe_close, persian(remaining))
        else -> stringResource(
            R.string.app_lock_wrong_wipe,
            persian(attempt),
            persian(AppLockWipePolicy.MAX_FAILED_ATTEMPTS),
        )
    }
}

internal fun persian(value: Int): String = VmTextFormat.digits(value.toString())
