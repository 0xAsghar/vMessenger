package ir.vmessenger.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.BubbleDirection
import ir.vmessenger.core.designsystem.component.DateSeparator
import ir.vmessenger.core.designsystem.component.MessageBubble
import ir.vmessenger.core.designsystem.component.ProgressPill
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.feature.chat.voice.VoiceBubbleHost

private const val INCOMING_CONTENT_TYPE = "incoming-transfer"

/**
 * The message list.
 *
 * `reverseLayout` keeps index 0 at the bottom, so an arriving message never shifts the
 * scroll anchor and paging backwards only ever appends to the end of the list.
 */
@Suppress("LongParameterList") // one collaborator per thing the list draws or reports
@Composable
internal fun ConversationMessageList(
    state: ConversationUiState,
    listState: LazyListState,
    actions: MessageActions,
    images: AttachmentImages,
    voice: VoiceBubbleHost,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.xxs, Alignment.Bottom),
        contentPadding = PaddingValues(vertical = VmSpacing.sm),
    ) {
        items(
            items = state.pendingIncoming,
            key = { "incoming-$it" },
            contentType = { INCOMING_CONTENT_TYPE },
        ) { messageId ->
            IncomingTransferBubble(progress = state.attachmentProgress[messageId])
        }
        items(
            items = state.items,
            key = { it.key },
            contentType = { it.contentType },
        ) { item ->
            when (item) {
                is ChatItem.Day -> DateSeparator(label = item.label)
                // A membership change is the conversation talking about itself: centred,
                // unowned by either side, and with nothing to reply to or long-press.
                is ChatItem.System -> DateSeparator(label = item.text)
                is ChatItem.Message -> SwipeToReply(onReply = { actions.onReply(item.messageId) }) {
                    MessageBubbleItem(
                        item = item,
                        contactName = state.header.title,
                        progress = state.attachmentProgress[item.messageId],
                        actions = actions,
                        images = images,
                        voice = voice,
                    )
                }
            }
        }
    }
}

/**
 * Drag a bubble toward the end of the line to reply. `StartToEnd` is direction aware, so
 * it is a right-swipe in a Latin layout and the left-swipe Persian users expect here.
 */
@Composable
private fun SwipeToReply(onReply: () -> Unit, content: @Composable () -> Unit) {
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onReply()
            // Never actually dismiss: the gesture is a shortcut, not a delete.
            false
        },
    )
    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = { ReplyAffordance() },
        enableDismissFromEndToStart = false,
    ) {
        content()
    }
}

@Composable
private fun ReplyAffordance() {
    Row(
        modifier = Modifier.padding(horizontal = VmSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Reply,
            contentDescription = stringResource(R.string.feature_chat_reply),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An attachment still arriving has no message row yet, so its progress gets a placeholder. */
@Composable
private fun IncomingTransferBubble(progress: AttachmentProgress?) {
    MessageBubble(direction = BubbleDirection.Incoming) {
        ProgressPill(
            label = stringResource(R.string.feature_chat_receiving),
            progress = progress?.fraction,
        )
    }
}
