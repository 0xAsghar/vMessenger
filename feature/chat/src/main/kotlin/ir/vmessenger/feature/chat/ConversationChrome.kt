package ir.vmessenger.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.AvatarVariant
import ir.vmessenger.core.designsystem.component.KeyChangeBanner
import ir.vmessenger.core.designsystem.component.ReplyPreview
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.component.VmBottomSheet
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * Avatar, name and the one line that matters: the members of a group, or — in a 1:1 chat —
 * verified, key changed, or blocked. Tapping it opens whoever the chat is with.
 */
@Composable
internal fun ConversationTitle(header: ConversationHeaderUi, navigation: ConversationNavigation) {
    val description = stringResource(
        if (header.isGroup) R.string.feature_chat_open_group else R.string.feature_chat_open_contact,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = header.contactId != null || header.groupId != null) {
                header.groupId?.let(navigation.onOpenGroup)
                    ?: header.contactId?.let(navigation.onOpenContact)
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        Avatar(
            seed = header.seed.bytes,
            name = header.title,
            size = VmSizes.avatarSm,
            variant = if (header.isGroup) AvatarVariant.Group else AvatarVariant.Person,
            contentDescription = description,
        )
        Column {
            Text(
                text = header.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitleText(header)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun subtitleText(header: ConversationHeaderUi): String? = when {
    header.isGroup -> header.memberNames
    header.blocked -> stringResource(R.string.feature_chat_subtitle_blocked)
    header.keyChangePending -> stringResource(R.string.feature_chat_subtitle_key_changed)
    header.verified -> stringResource(R.string.feature_chat_subtitle_verified)
    else -> null
}

/**
 * Key change first (it is a security decision), then the blocked and group
 * notices. Closed outranks out of sync: a closed group has no membership left to
 * be behind on, so saying both would only muddle it.
 */
@Composable
internal fun ConversationBanners(header: ConversationHeaderUi, onOpenContact: (String) -> Unit) {
    if (header.keyChangePending) {
        KeyChangeBanner(
            contactName = header.title,
            onVerify = { header.contactId?.let(onOpenContact) },
        )
    }
    if (header.blocked) {
        ConversationBanner(
            icon = Icons.Outlined.Block,
            textRes = R.string.feature_chat_blocked_banner,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    when {
        // A closed group stays readable; the composer is gone, so the banner says why.
        header.closed -> ConversationBanner(
            icon = Icons.Outlined.Lock,
            textRes = R.string.feature_chat_group_closed_banner,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Only the creator can hand out the membership, and this one has stopped
        // answering. Messages still flow; who is in the group no longer moves, which
        // is the part nothing else on this screen would ever admit.
        header.outOfSync -> ConversationBanner(
            icon = Icons.Outlined.SyncProblem,
            textRes = R.string.feature_chat_group_out_of_sync_banner,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One shape for every notice above the message list: icon, one line, full width. */
@Composable
private fun ConversationBanner(icon: ImageVector, textRes: Int, color: Color, contentColor: Color) {
    Surface(color = color, contentColor = contentColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(VmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Text(
                text = stringResource(textRes),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun JumpToBottomFab(visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = stringResource(R.string.feature_chat_jump_to_bottom),
        )
    }
}

/** The composer's reply strip needs a rendered sender name, which only composition knows. */
@Composable
internal fun rememberReplyPreview(reply: ReplyQuoteUi, contactName: String): ReplyPreview {
    val self = stringResource(R.string.feature_chat_reply_self)
    return remember(reply, contactName, self) {
        ReplyPreview(
            messageId = reply.messageId,
            senderName = if (reply.senderIsMe) self else contactName,
            preview = reply.preview,
        )
    }
}

/**
 * Long-press menu of a single message. Forwarding is a documented non-goal for 1.0.
 *
 * Built to match `ContactActionsSheet`, which is the house pattern: an explicit sheet state, the
 * navigation-bar inset rather than a fixed bottom pad (which was short on gesture-navigation
 * devices), a header naming what is being acted on, design-system rows with a guaranteed touch
 * target, and destructive actions tinted through `LocalContentColor`. It previously used raw
 * `ListItem`s with none of that, so the same gesture produced two different-looking sheets.
 *
 * "Information" sits at the end, after the everyday actions and before the destructive one.
 */
@Composable
internal fun MessageActionsSheet(
    preview: String,
    abilities: MessageAbilities,
    actions: MessageSheetActions,
) {
    VmBottomSheet(title = preview, onDismiss = actions.onDismiss) {
        SheetAction(
            R.string.feature_chat_reply,
            Icons.AutoMirrored.Outlined.Reply,
            actions.onDismiss,
            actions.onReply,
        )
        if (abilities.canEdit) {
            SheetAction(R.string.feature_chat_edit_message, Icons.Outlined.Edit, actions.onDismiss, actions.onEdit)
        }
        if (abilities.canCopy) {
            SheetAction(R.string.feature_chat_copy, Icons.Outlined.ContentCopy, actions.onDismiss, actions.onCopy)
        }
        // Only once the file is actually here: there is nothing to hand another app until then.
        if (abilities.canShare) {
            SheetAction(R.string.feature_chat_share, Icons.Outlined.Share, actions.onDismiss, actions.onShare)
        }
        SheetAction(R.string.feature_chat_message_info, Icons.Outlined.Info, actions.onDismiss, actions.onInfo)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
            SheetAction(
                R.string.feature_chat_delete_message,
                Icons.Outlined.DeleteOutline,
                actions.onDismiss,
                { actions.onDelete(false) },
            )
            // Only offered for our own messages, and worded as a request: a peer can ignore
            // the control and nothing here can verify that they did not.
            if (abilities.canDeleteForEveryone) {
                SheetAction(
                    R.string.feature_chat_delete_for_everyone,
                    Icons.Outlined.DeleteSweep,
                    actions.onDismiss,
                    { actions.onDelete(true) },
                )
            }
        }
    }
}

/** What this particular message allows; each is false for a reason the sheet should not restate. */
@Immutable
internal data class MessageAbilities(
    val canCopy: Boolean,
    val canEdit: Boolean,
    val canDeleteForEveryone: Boolean,
    val canShare: Boolean,
)

/** Names the message being acted on, the way the contact sheet names the contact. */
@Composable
private fun SheetAction(labelRes: Int, icon: ImageVector, onDismiss: () -> Unit, onAct: () -> Unit) {
    SettingsRow(
        label = stringResource(labelRes),
        icon = icon,
        trailing = SettingsTrailing.None,
        onClick = {
            onDismiss()
            onAct()
        },
    )
}
