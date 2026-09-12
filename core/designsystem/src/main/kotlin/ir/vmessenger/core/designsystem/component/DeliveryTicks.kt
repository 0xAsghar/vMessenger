package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.vm

private val TickSize = 16.dp

/**
 * Delivery state as an icon rather than a word: clock, one check, two checks, two accented
 * checks, error. The Persian state name becomes the icon's content description, so screen
 * readers still announce it.
 */
@Composable
fun DeliveryTicks(
    state: DeliveryTicksState,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val icon = when (state) {
        DeliveryTicksState.QUEUED -> Icons.Outlined.Schedule
        DeliveryTicksState.SENT -> Icons.Outlined.Check
        DeliveryTicksState.DELIVERED, DeliveryTicksState.READ -> Icons.Outlined.DoneAll
        DeliveryTicksState.FAILED -> Icons.Outlined.ErrorOutline
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(state.labelRes()),
        tint = if (tint == Color.Unspecified) defaultTint(state) else tint,
        modifier = modifier.size(TickSize),
    )
}

private fun DeliveryTicksState.labelRes(): Int = when (this) {
    DeliveryTicksState.QUEUED -> R.string.vm_tick_queued
    DeliveryTicksState.SENT -> R.string.vm_tick_sent
    DeliveryTicksState.DELIVERED -> R.string.vm_tick_delivered
    DeliveryTicksState.READ -> R.string.vm_tick_read
    DeliveryTicksState.FAILED -> R.string.vm_tick_failed
}

@Composable
private fun defaultTint(state: DeliveryTicksState): Color = when (state) {
    DeliveryTicksState.QUEUED -> MaterialTheme.vm.tickPending
    DeliveryTicksState.SENT, DeliveryTicksState.DELIVERED -> MaterialTheme.vm.tickSent
    DeliveryTicksState.READ -> MaterialTheme.vm.tickRead
    DeliveryTicksState.FAILED -> MaterialTheme.colorScheme.error
}
