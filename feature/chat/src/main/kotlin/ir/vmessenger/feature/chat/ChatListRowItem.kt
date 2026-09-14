package ir.vmessenger.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.ChatListItem
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.domain.model.MessagePreviewKind

/**
 * One chats-tab row. The media icon prefix lives in the string resources
 * ("📷 عکس", "📎 %1$s"), so the preview line is a single lookup rather than an icon
 * composable squeezed into a text row.
 */
@Composable
internal fun ChatListRowItem(
    row: ChatListRow,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    ChatListItem(
        title = row.title,
        subtitle = previewLine(row),
        time = row.time,
        // While a selection is open a tap toggles it instead of opening the chat.
        onClick = { if (selectionMode) onLongPress(row.id) else onOpen(row.id) },
        onLongClick = { onLongPress(row.id) },
        unreadCount = row.unreadCount,
        muted = row.muted,
        ticks = row.ticks,
        selected = selected,
    ) {
        Avatar(seed = row.seed.bytes, name = row.title, size = VmSizes.avatarMd)
    }
}

@Composable
private fun previewLine(row: ChatListRow): AnnotatedString {
    val text = when (row.previewKind) {
        MessagePreviewKind.IMAGE -> stringResource(R.string.feature_chat_preview_image)
        MessagePreviewKind.VIDEO -> stringResource(R.string.feature_chat_preview_video)
        MessagePreviewKind.FILE ->
            stringResource(R.string.feature_chat_preview_file, VmTextFormat.isolate(row.preview.orEmpty()))
        MessagePreviewKind.AUDIO -> stringResource(R.string.feature_chat_preview_audio)
        MessagePreviewKind.LOCATION -> stringResource(R.string.feature_chat_preview_location)
        MessagePreviewKind.DELETED -> stringResource(R.string.feature_chat_preview_deleted)
        // A membership line is already a full sentence; the sender prefix would only repeat it.
        MessagePreviewKind.GROUP_EVENT -> row.preview.orEmpty()
        MessagePreviewKind.TEXT, MessagePreviewKind.OTHER -> row.preview.orEmpty()
        null -> stringResource(R.string.feature_chat_preview_empty)
    }
    val sender = row.senderName?.takeIf { row.previewKind != MessagePreviewKind.GROUP_EVENT }
    val line = if (sender == null) {
        text
    } else {
        stringResource(R.string.feature_chat_preview_sender, VmTextFormat.isolate(sender), text)
    }
    return remember(line) { AnnotatedString(line) }
}
