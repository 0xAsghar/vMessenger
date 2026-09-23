package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * One row of the chats tab: avatar slot, title, preview line (already styled by the caller as an
 * [AnnotatedString]), time, unread count, mute icon and optional outgoing ticks.
 *
 * Unread is carried by the accent, as in Element X: the time turns the accent colour and the count
 * sits in an accent pill — grey instead when the conversation is muted, since a muted chat is
 * information rather than a call. The geometry is [VmListRow]'s; only the chat-specific slots and
 * the long-press gesture live here.
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
    val background by animateColorAsState(
        targetValue = if (selected) VmTheme.colors.bgAccentSubtle else Color.Transparent,
        animationSpec = VmMotion.emphasis(),
        label = "row-selection",
    )
    VmListRow(
        title = title,
        modifier = modifier
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        subtitle = { PreviewLine(subtitle = subtitle, ticks = ticks) },
        trailing = { TrailingColumn(time = time, unreadCount = unreadCount, muted = muted) },
        avatar = avatar,
    )
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
        VmText(
            text = subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrailingColumn(time: String, unreadCount: Int, muted: Boolean) {
    val c = VmTheme.colors
    val unread = unreadCount > 0
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.xs),
    ) {
        VmText(
            text = time,
            style = if (unread) VmTheme.typography.bodySmMedium else VmTheme.typography.bodySm,
            color = if (unread && !muted) c.textAccent else c.textSecondary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs),
        ) {
            if (muted) {
                VmIcon(
                    imageVector = Icons.Outlined.NotificationsOff,
                    contentDescription = stringResource(R.string.vm_chat_muted),
                    tint = c.iconTertiary,
                    size = VmSizes.iconSm,
                )
            }
            if (unread) {
                val label = VmTextFormat.digits(unreadCount.toString())
                val description = stringResource(R.string.vm_chat_unread_count, label)
                VmBadge(
                    text = label,
                    emphasized = !muted,
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
        }
    }
}
