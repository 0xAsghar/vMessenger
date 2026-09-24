package ir.vmessenger.feature.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.component.BubbleDirection
import ir.vmessenger.core.designsystem.component.BubbleMeta
import ir.vmessenger.core.designsystem.component.DeliveryTicksState
import ir.vmessenger.core.designsystem.component.FileBubbleContent
import ir.vmessenger.core.designsystem.component.ImageBubbleContent
import ir.vmessenger.core.designsystem.component.MessageBubble
import ir.vmessenger.core.designsystem.component.MessageBubbleDefaults
import ir.vmessenger.core.designsystem.component.ReplyQuote
import ir.vmessenger.core.designsystem.component.TextBubbleContent
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.AttachmentType
import ir.vmessenger.domain.model.MessagePreviewKind
import ir.vmessenger.feature.chat.voice.VoiceBubbleContent
import ir.vmessenger.feature.chat.voice.VoiceBubbleHost
import ir.vmessenger.core.designsystem.R as DesignSystemR

/**
 * One message: reply quote, payload, and the time + ticks line inside the bubble. A failed
 * send grows an error line and a retry button underneath, on the bubble's own side.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList") // one collaborator per thing a bubble draws or reports
internal fun MessageBubbleItem(
    item: ChatItem.Message,
    contactName: String,
    progress: AttachmentProgress?,
    actions: MessageActions,
    images: AttachmentImages,
    voice: VoiceBubbleHost,
    highlighted: Boolean = false,
) {
    val direction = if (item.outgoing) BubbleDirection.Outgoing else BubbleDirection.Incoming
    val ticksLabel = item.ticks?.let { ticksLabel(it) }
    val base = MessageBubbleDefaults.colors(direction)
    // Animated in both directions, so the highlight fades out rather than snapping back when the
    // two seconds are up. MessageBubble already takes a colors parameter; nothing new is needed.
    val container by animateColorAsState(
        targetValue = if (highlighted) VmTheme.colors.bubbleHighlight else base.container,
        animationSpec = VmMotion.emphasis(),
        label = "bubble-highlight",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        MessageBubble(
            direction = direction,
            colors = base.copy(container = container),
            // Only a text reply. The quote needs the bubble's width, but intrinsic measurement
            // asks every child for one — and an attachment payload (a waveform Canvas, an image
            // still loading) reports zero, which would collapse the bubble around it.
            matchWidestChild = item.reply != null && item.attachment == null,
            // A raw long-press detector rather than combinedClickable: the bubble's row is
            // full width, so a click modifier would ripple across the empty half of it.
            modifier = Modifier
                .pointerInput(item.messageId) {
                    detectTapGestures(onLongPress = { actions.onLongPress(item.messageId) })
                }
                .semantics { ticksLabel?.let { stateDescription = it } },
        ) {
            SenderLabel(name = item.senderName?.takeIf { item.startsSenderRun }, seed = item.senderSeed)
            BubbleBody(
                item = item,
                contactName = contactName,
                progress = progress,
                actions = actions,
                images = images,
                voice = voice,
            )
            BubbleMeta(
                edited = item.edited,
                expiring = item.expiring,
                time = item.time,
                modifier = Modifier.align(Alignment.End),
                ticks = item.ticks,
            )
        }
        if (item.failed) {
            FailureLine(outgoing = item.outgoing, errorCode = item.errorCode) { actions.onRetry(item.messageId) }
        }
    }
}

/**
 * Who sent this, in a group. Only on the first bubble of a run — the caller passes a null [name]
 * everywhere else: repeating it above every message of the same person is noise, and the colour
 * already carries the identity.
 */
@Composable
internal fun SenderLabel(name: String?, seed: IdentitySeed?) {
    if (name == null) return
    VmText(
        text = name,
        style = VmTheme.typography.bodySmMedium,
        color = seed?.let { VmTheme.colors.senderColor(it.bytes) }
            ?: VmTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = VmSpacing.xxs),
    )
}

@Suppress("LongParameterList") // one collaborator per payload kind the body can draw
@Composable
private fun BubbleBody(
    item: ChatItem.Message,
    contactName: String,
    progress: AttachmentProgress?,
    actions: MessageActions,
    images: AttachmentImages,
    voice: VoiceBubbleHost,
) {
    if (item.deleted) {
        // Italic and muted, so a tombstone never passes for something the sender wrote.
        VmText(
            text = stringResource(R.string.feature_chat_message_deleted),
            style = VmTheme.typography.bodyLg.copy(fontStyle = FontStyle.Italic),
            color = VmTheme.colors.textSecondary,
        )
        return
    }
    item.reply?.let { reply ->
        ReplyQuote(
            senderName = if (reply.senderIsMe) stringResource(R.string.feature_chat_reply_self) else contactName,
            preview = quotePreview(reply),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = VmSpacing.xs),
            onClick = { actions.onJumpToQuoted(reply.messageId) },
        )
    }
    item.attachment?.let { attachment ->
        if (attachment.type == AttachmentType.AUDIO) {
            VoiceBody(item = item, attachment = attachment, voice = voice, actions = actions)
        } else {
            AttachmentBody(
                item = item,
                attachment = attachment,
                progress = progress?.fraction,
                actions = actions,
                images = images,
            )
        }
    }
    if (item.text.isNotBlank()) {
        TextBubbleContent(text = item.text)
    }
}

/**
 * A voice message. The dot marks one that has never been played — the audio counterpart of
 * an unread row — and only makes sense on a message somebody else sent.
 */
@Composable
private fun VoiceBody(
    item: ChatItem.Message,
    attachment: AttachmentUi,
    voice: VoiceBubbleHost,
    actions: MessageActions,
) {
    VoiceBubbleContent(
        waveform = attachment.waveform,
        durationMs = attachment.durationMs ?: 0L,
        playback = voice.stateFor(item.messageId, unplayed = !item.outgoing && attachment.unplayed),
        onPlayPause = { voice.onToggle(item.messageId) },
        onSeek = voice.onSeek,
        onToggleSpeed = voice.onToggleSpeed,
        onLongPress = { actions.onLongPress(item.messageId) },
    )
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

/** Why a send failed, and the retry, on the bubble's own side underneath it. */
@Composable
internal fun FailureLine(outgoing: Boolean, errorCode: String?, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.md),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VmText(
            text = sendErrorText(errorCode),
            style = VmTheme.typography.bodyXsMedium,
            color = VmTheme.colors.textCritical,
        )
        VmTextButton(text = stringResource(R.string.feature_chat_retry), onClick = onRetry)
    }
}

/** What a quote says about the message it quotes: its words, or what kind of thing it is. */
@Composable
internal fun quotePreview(reply: ReplyQuoteUi): String = when (reply.kind) {
    MessagePreviewKind.IMAGE -> stringResource(R.string.feature_chat_preview_image)
    MessagePreviewKind.VIDEO -> stringResource(R.string.feature_chat_preview_video)
    MessagePreviewKind.FILE -> stringResource(R.string.feature_chat_preview_file, VmTextFormat.isolate(reply.preview))
    MessagePreviewKind.AUDIO -> stringResource(R.string.feature_chat_preview_audio)
    MessagePreviewKind.LOCATION -> stringResource(R.string.feature_chat_preview_location)
    MessagePreviewKind.DELETED -> stringResource(R.string.feature_chat_preview_deleted)
    MessagePreviewKind.GROUP_EVENT,
    MessagePreviewKind.TEXT,
    MessagePreviewKind.OTHER,
    -> reply.preview
}

/** The delivery state as a sentence, for `stateDescription` on the bubble. */
@Composable
internal fun ticksLabel(state: DeliveryTicksState): String = stringResource(
    when (state) {
        DeliveryTicksState.QUEUED -> DesignSystemR.string.vm_tick_queued
        DeliveryTicksState.SENT -> DesignSystemR.string.vm_tick_sent
        DeliveryTicksState.DELIVERED -> DesignSystemR.string.vm_tick_delivered
        DeliveryTicksState.READ -> DesignSystemR.string.vm_tick_read
        DeliveryTicksState.FAILED -> DesignSystemR.string.vm_tick_failed
    },
)
