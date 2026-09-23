package ir.vmessenger.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.VmDropdownMenu
import ir.vmessenger.core.designsystem.component.VmDropdownMenuItem
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor
import ir.vmessenger.core.designsystem.theme.VmTheme
import java.util.concurrent.TimeUnit

/** A self-destruct duration offered by [ConversationTimerAction]; a null duration is "off". */
private data class TimerOption(val labelRes: Int, val durationMs: Long?)

private val TIMER_OPTIONS = listOf(
    TimerOption(R.string.feature_chat_timer_off, null),
    TimerOption(R.string.feature_chat_timer_1h, TimeUnit.HOURS.toMillis(1)),
    TimerOption(R.string.feature_chat_timer_24h, TimeUnit.HOURS.toMillis(24)),
    TimerOption(R.string.feature_chat_timer_7d, TimeUnit.DAYS.toMillis(7)),
)

/**
 * Header control for the chat's disappearing-message timer: a clock, tinted while a timer is set,
 * opening a menu of durations. The choice is a per-conversation default applied to new messages;
 * expiry is enforced on each device, so it is best-effort against an honest client, not a guarantee.
 */
@Composable
internal fun ConversationTimerAction(selectedMs: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // The menu anchors to what it shares a box with: the clock, not the whole bar.
    Box {
        VmIconButton(
            icon = Icons.Outlined.Timer,
            contentDescription = stringResource(R.string.feature_chat_timer),
            onClick = { expanded = true },
            tint = if (selectedMs != null) VmTheme.colors.iconAccent else LocalVmContentColor.current,
        )
        VmDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in TIMER_OPTIONS) {
                VmDropdownMenuItem(
                    text = stringResource(option.labelRes),
                    selected = option.durationMs == selectedMs,
                    onClick = {
                        onSelect(option.durationMs)
                        expanded = false
                    },
                )
            }
        }
    }
}
