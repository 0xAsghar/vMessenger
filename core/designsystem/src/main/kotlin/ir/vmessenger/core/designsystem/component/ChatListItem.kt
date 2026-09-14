package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing

private val MutedIconSize = 16.dp
private const val SELECTED_ALPHA = 0.16f

/**
 * One row of the chats tab: avatar slot, title, preview line (already styled by the caller as an
 * [AnnotatedString]), time, unread pill, mute icon and optional outgoing ticks.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongParameterList") // Compose slot API: each element of the row is independently supplied.
@Composable
fun ChatListItem(
    title: String,
    subtitle: AnnotatedString,
    time: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    muted: Boolean = false,
    ticks: DeliveryTicksState? = null,
    selected: Boolean = false,
    avatar: @Composable () -> Unit,
) {
    val targetBackground = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_ALPHA)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = VmMotion.emphasis(),
        label = "row-selection",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = VmSizes.listItemHeight)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        avatar()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PreviewLine(subtitle = subtitle, ticks = ticks)
        }
        TrailingColumn(time = time, unreadCount = unreadCount, muted = muted)
    }
}

@Composable
private fun PreviewLine(subtitle: AnnotatedString, ticks: DeliveryTicksState?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs),
    ) {
        if (ticks != null) {
            DeliveryTicks(state = ticks)
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrailingColumn(time: String, unreadCount: Int, muted: Boolean) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.xs),
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs),
        ) {
            if (muted) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsOff,
                    contentDescription = stringResource(R.string.vm_chat_muted),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MutedIconSize),
                )
            }
            UnreadPill(unreadCount)
        }
    }
}

@Composable
private fun UnreadPill(count: Int) {
    if (count <= 0) return
    val label = VmTextFormat.persianDigits(count.toString())
    val description = stringResource(R.string.vm_chat_unread_count, label)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xxs),
        )
    }
}
