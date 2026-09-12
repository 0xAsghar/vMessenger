package ir.vmessenger.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ir.vmessenger.core.designsystem.component.BubbleDirection
import ir.vmessenger.core.designsystem.component.BubbleMeta
import ir.vmessenger.core.designsystem.component.DeliveryTicksState
import ir.vmessenger.core.designsystem.component.FileBubbleContent
import ir.vmessenger.core.designsystem.component.ImageBubbleContent
import ir.vmessenger.core.designsystem.component.MessageBubble
import ir.vmessenger.core.designsystem.component.ReplyQuote
import ir.vmessenger.core.designsystem.component.TextBubbleContent
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.AttachmentType
import ir.vmessenger.domain.model.MessagePreviewKind
import ir.vmessenger.core.designsystem.R as DesignSystemR

/**
 * One message: reply quote, payload, and the time + ticks line inside the bubble. A failed
 * send grows an error line and a retry button underneath, on the bubble's own side.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubbleItem(
    item: ChatItem.Message,
    contactName: String,
    progress: AttachmentProgress?,
    actions: MessageActions,
    images: AttachmentImages,
) {
    val direction = if (item.outgoing) BubbleDirection.Outgoing else BubbleDirection.Incoming
    val ticksLabel = item.ticks?.let { ticksLabel(it) }
    Column(modifier = Modifier.fillMaxWidth()) {
        MessageBubble(
            direction = direction,
            // A raw long-press detector rather than combinedClickable: the bubble's row is
            // full width, so a click modifier would ripple across the empty half of it.
            modifier = Modifier
                .pointerInput(item.messageId) {
                    detectTapGestures(onLongPress = { actions.onLongPress(item.messageId) })
                }
                .semantics { ticksLabel?.let { stateDescription = it } },
        ) {
            BubbleBody(item = item, contactName = contactName, progress = progress, actions = actions, images = images)
            BubbleMeta(
                time = item.time,
                modifier = Modifier.align(Alignment.End),
                ticks = item.ticks,
            )
        }
        if (item.failed) {
            FailureLine(item = item, onRetry = actions.onRetry)
        }
    }
}

@Composable
private fun BubbleBody(
    item: ChatItem.Message,
    contactName: String,
    progress: AttachmentProgress?,
    actions: MessageActions,
    images: AttachmentImages,
) {
    item.reply?.let { reply ->
        ReplyQuote(
            senderName = if (reply.senderIsMe) stringResource(R.string.feature_chat_reply_self) else contactName,
            preview = quotePreview(reply),
            modifier = Modifier.padding(bottom = VmSpacing.xs),
            onClick = { actions.onJumpToQuoted(reply.messageId) },
        )
    }
    item.attachment?.let { attachment ->
        AttachmentBody(
            item = item,
            attachment = attachment,
            progress = progress?.fraction,
            actions = actions,
            images = images,
        )
    }
    if (item.text.isNotBlank()) {
        TextBubbleContent(text = item.text)
    }
}

@Composable
private fun AttachmentBody(
    item: ChatItem.Message,
    attachment: AttachmentUi,
    progress: Float?,
    actions: MessageActions,
    images: AttachmentImages,
) {
    val context = LocalContext.current
    val messageId = item.messageId
    // Only a fully received image is decoded in-process; everything else is a file row, which
    // also covers video (no frame decoder is bundled, and decrypting one to disk to build a
    // thumbnail would defeat encryption at rest).
    if (attachment.type == AttachmentType.IMAGE && attachment.available) {
        val request = remember(messageId, context) { images.request(context, messageId) }
        ImageBubbleContent(
            model = request,
            modifier = Modifier.combinedClickable(
                onClick = { actions.onOpenImage(messageId) },
                onLongClick = { actions.onLongPress(messageId) },
            ),
            contentDescription = stringResource(R.string.feature_chat_preview_image),
            progress = progress,
        )
    } else {
        FileBubbleContent(
            name = attachment.fileName,
            sizeBytes = attachment.sizeBytes,
            progress = progress,
            onClick = if (attachment.available) {
                { actions.onOpenFile(item) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun FailureLine(item: ChatItem.Message, onRetry: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.md),
        horizontalArrangement = if (item.outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sendErrorText(item.errorCode),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { onRetry(item.messageId) }) {
            Text(text = stringResource(R.string.feature_chat_retry))
        }
    }
}

@Composable
private fun quotePreview(reply: ReplyQuoteUi): String = when (reply.kind) {
    MessagePreviewKind.IMAGE -> stringResource(R.string.feature_chat_preview_image)
    MessagePreviewKind.VIDEO -> stringResource(R.string.feature_chat_preview_video)
    MessagePreviewKind.FILE -> stringResource(R.string.feature_chat_preview_file, reply.preview)
    MessagePreviewKind.LOCATION -> stringResource(R.string.feature_chat_preview_location)
    MessagePreviewKind.TEXT, MessagePreviewKind.OTHER -> reply.preview
}

/** The delivery state as a sentence, for `stateDescription` on the bubble. */
@Composable
private fun ticksLabel(state: DeliveryTicksState): String = stringResource(
    when (state) {
        DeliveryTicksState.QUEUED -> DesignSystemR.string.vm_tick_queued
        DeliveryTicksState.SENT -> DesignSystemR.string.vm_tick_sent
        DeliveryTicksState.DELIVERED -> DesignSystemR.string.vm_tick_delivered
        DeliveryTicksState.READ -> DesignSystemR.string.vm_tick_read
        DeliveryTicksState.FAILED -> DesignSystemR.string.vm_tick_failed
    },
)
