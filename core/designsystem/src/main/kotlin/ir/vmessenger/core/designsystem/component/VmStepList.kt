package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

enum class VmStepState { Pending, Running, Done, Warning, Failed, Skipped }

data class VmStep(val label: String, val state: VmStepState, val note: String? = null)

/**
 * A process as a list of steps, each pending, running, done, done with a warning, failed or
 * skipped. A live region: a screen reader announces a step as its state changes.
 */
@Composable
fun VmStepList(steps: List<VmStep>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(VmSpacing.md)) {
        steps.forEach { step -> StepRow(step) }
    }
}

@Composable
private fun StepRow(step: VmStep) {
    val c = VmTheme.colors
    val state = stringResource(stateText(step.state))
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(ICON), contentAlignment = Alignment.Center) {
            when (step.state) {
                VmStepState.Running -> VmProgressIndicator(modifier = Modifier.size(ICON - 4.dp))
                VmStepState.Done -> VmIcon(Icons.Rounded.CheckCircle, contentDescription = state, tint = c.iconSuccess)
                VmStepState.Warning -> VmIcon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = state,
                    tint = c.textWarning
                )
                VmStepState.Failed -> VmIcon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = state,
                    tint = c.iconCritical
                )
                VmStepState.Skipped -> VmIcon(
                    Icons.Outlined.RemoveCircleOutline,
                    contentDescription = state,
                    tint = c.iconTertiary
                )
                VmStepState.Pending -> VmIcon(Icons.Outlined.Circle, contentDescription = state, tint = c.iconTertiary)
            }
        }
        Column {
            val labelStyle = if (step.state == VmStepState.Running) {
                VmTheme.typography.bodyMdMedium
            } else {
                VmTheme.typography.bodyMd
            }
            VmText(
                text = step.label,
                style = labelStyle,
                color = if (step.state == VmStepState.Pending) c.textSecondary else c.textPrimary,
            )
            if (step.note != null) VmText(text = step.note, style = VmTheme.typography.bodySm, color = c.textSecondary)
        }
    }
}

private fun stateText(state: VmStepState): Int = when (state) {
    VmStepState.Pending -> R.string.vm_step_pending
    VmStepState.Running -> R.string.vm_step_running
    VmStepState.Done -> R.string.vm_step_done
    VmStepState.Warning -> R.string.vm_step_warning
    VmStepState.Failed -> R.string.vm_step_failed
    VmStepState.Skipped -> R.string.vm_step_skipped
}

private val ICON = 24.dp
