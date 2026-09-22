package ir.vmessenger.feature.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
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
    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Outlined.Timer,
            contentDescription = stringResource(R.string.feature_chat_timer),
            tint = if (selectedMs != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        for (option in TIMER_OPTIONS) {
            DropdownMenuItem(
                text = { Text(text = stringResource(option.labelRes)) },
                onClick = {
                    onSelect(option.durationMs)
                    expanded = false
                },
                trailingIcon = if (option.durationMs == selectedMs) {
                    { Icon(imageVector = Icons.Outlined.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}
