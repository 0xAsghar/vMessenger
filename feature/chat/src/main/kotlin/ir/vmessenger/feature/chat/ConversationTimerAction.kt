package ir.vmessenger.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.VmDateTimePickerDialog
import ir.vmessenger.core.designsystem.component.VmDropdownMenu
import ir.vmessenger.core.designsystem.component.VmDropdownMenuItem
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor
import ir.vmessenger.core.designsystem.theme.VmTheme
import java.util.concurrent.TimeUnit

/** A self-destruct choice offered by [ConversationTimerAction]; a null timer is "off". */
private data class TimerOption(val labelRes: Int, val timer: MessageTimer?)

private val TIMER_OPTIONS = listOf(
    TimerOption(R.string.feature_chat_timer_off, null),
    TimerOption(R.string.feature_chat_timer_1h, MessageTimer.After(TimeUnit.HOURS.toMillis(1))),
    TimerOption(R.string.feature_chat_timer_24h, MessageTimer.After(TimeUnit.HOURS.toMillis(24))),
    TimerOption(R.string.feature_chat_timer_7d, MessageTimer.After(TimeUnit.DAYS.toMillis(7))),
)

/** The earliest moment worth offering: a deadline a minute away is already a race with the send. */
private val MIN_LEAD_MS = TimeUnit.MINUTES.toMillis(1)

/** A year ahead: long enough for any plan, short enough that a mistyped year is caught. */
private val MAX_AHEAD_MS = TimeUnit.DAYS.toMillis(365)
private val FIVE_MINUTES_MS = TimeUnit.MINUTES.toMillis(5)

/**
 * Header control for the chat's disappearing-message timer: a clock, tinted while a timer is set,
 * opening a menu of durations and, last, a date and time. The choice is a per-conversation default
 * applied to new messages; expiry is enforced on each device, so it is best-effort against an
 * honest client, not a guarantee.
 */
@Composable
internal fun ConversationTimerAction(selected: MessageTimer?, onSelect: (MessageTimer?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val at = selected as? MessageTimer.At
    // The menu anchors to what it shares a box with: the clock, not the whole bar.
    Box {
        VmIconButton(
            icon = Icons.Outlined.Timer,
            contentDescription = stringResource(R.string.feature_chat_timer),
            onClick = { expanded = true },
            tint = if (selected != null) VmTheme.colors.iconAccent else LocalVmContentColor.current,
        )
        VmDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in TIMER_OPTIONS) {
                VmDropdownMenuItem(
                    text = stringResource(option.labelRes),
                    selected = option.timer == selected,
                    onClick = {
                        onSelect(option.timer)
                        expanded = false
                    },
                )
            }
            VmDropdownMenuItem(
                text = if (at != null) {
                    stringResource(R.string.feature_chat_timer_at_chosen, VmDateFormat.dayAndTime(at.atUnixMs))
                } else {
                    stringResource(R.string.feature_chat_timer_at)
                },
                selected = at != null,
                onClick = {
                    expanded = false
                    picking = true
                },
            )
        }
    }
    if (picking) {
        TimerDeadlinePicker(
            current = at,
            onPicked = {
                onSelect(MessageTimer.At(it))
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun TimerDeadlinePicker(current: MessageTimer.At?, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    VmDateTimePickerDialog(
        title = stringResource(R.string.feature_chat_timer_at_title),
        description = stringResource(R.string.feature_chat_timer_at_body),
        confirmLabel = stringResource(R.string.feature_chat_timer_at_confirm),
        range = now + MIN_LEAD_MS..now + MAX_AHEAD_MS,
        // An hour from now, on a five-minute mark, unless a moment was already chosen.
        initialMs = current?.atUnixMs ?: ((now / FIVE_MINUTES_MS + 1) * FIVE_MINUTES_MS + TimeUnit.HOURS.toMillis(1)),
        onConfirm = onPicked,
        onDismiss = onDismiss,
    )
}
