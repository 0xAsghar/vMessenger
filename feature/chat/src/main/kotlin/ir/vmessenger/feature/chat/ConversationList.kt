package ir.vmessenger.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.component.BubbleDirection
import ir.vmessenger.core.designsystem.component.DateSeparator
import ir.vmessenger.core.designsystem.component.MessageBubble
import ir.vmessenger.core.designsystem.component.ProgressPill
import ir.vmessenger.core.designsystem.component.SystemMessage
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.feature.chat.voice.VoiceBubbleHost
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val INCOMING_CONTENT_TYPE = "incoming-transfer"

/** How far a bubble must travel before letting go replies to it. */
private val REPLY_THRESHOLD = 64.dp

/** Past this the bubble stops following: the gesture has said what it means. */
private val REPLY_MAX_TRAVEL = 96.dp

/** The bubble moves this share of the finger's travel, so it feels held rather than loose. */
private const val RESISTANCE = 0.6f

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
            IncomingTransferBubble(
                progress = state.attachmentProgress[messageId],
                modifier = Modifier.animateItem(placementSpec = tween(VmMotion.PLACEMENT_MS)),
            )
        }
        items(
            items = state.items,
            key = { it.key },
            contentType = { it.contentType },
        ) { item ->
            // Appearance carries the motion; placement stays short. In a reverseLayout list a
            // generous placement spec slides the entire column whenever a message lands.
            val animated = Modifier.animateItem(placementSpec = tween(VmMotion.PLACEMENT_MS))
            when (item) {
                is ChatItem.Day -> DateSeparator(label = item.label, modifier = animated)
                // A membership change is the conversation talking about itself: centred,
                // unowned by either side, and with nothing to reply to or long-press.
                is ChatItem.System -> SystemMessage(text = item.text, modifier = animated)
                is ChatItem.Message -> SwipeToReply(
                    onReply = { actions.onReply(item.messageId) },
                    modifier = animated,
                ) {
                    MessageBubbleItem(
                        item = item,
                        contactName = state.header.title,
                        progress = state.attachmentProgress[item.messageId],
                        actions = actions,
                        images = images,
                        voice = voice,
                        highlighted = item.messageId == state.highlightedMessageId,
                    )
                }
                // Swiping an album quotes its first photo, the one the grid starts with.
                is ChatItem.Album -> SwipeToReply(
                    onReply = { actions.onReply(item.first.messageId) },
                    modifier = animated,
                ) {
                    AlbumBubbleItem(
                        album = item,
                        progress = state.attachmentProgress,
                        actions = actions,
                        images = images,
                        highlighted = state.highlightedMessageId?.let { it in item } == true,
                    )
                }
            }
        }
    }
}

/**
 * Drag a bubble toward the end of the line to reply: a right-swipe in English, the left-swipe
 * Persian users expect. The bubble follows the finger with some resistance, a reply arrow fades in
 * where it came from, and crossing the threshold gives a tick of haptics — let go past it and the
 * reply opens, let go short of it and nothing happens. Either way the bubble springs back.
 *
 * Only a drag *toward the end* is taken. One toward the start is left for whatever is underneath
 * — in Persian that is the conversation's own swipe back, which the Material box this replaced
 * swallowed on every bubble, so going back only worked from the gaps between messages.
 */
@Composable
private fun SwipeToReply(
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val threshold = with(density) { REPLY_THRESHOLD.toPx() }
    val maxTravel = with(density) { REPLY_MAX_TRAVEL.toPx() }
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val reply by rememberUpdatedState(onReply)
    Box(
        modifier = modifier.pointerInput(rtl, threshold, maxTravel) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var armed = false
                val towardEnd = { dx: Float -> if (rtl) -dx else dx }
                val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    // Not consuming a start-ward slop leaves the drag to the swipe back.
                    if (towardEnd(over) > 0f) change.consume()
                } ?: return@awaitEachGesture
                horizontalDrag(start.id) { change ->
                    val next = (offset.value + towardEnd(change.positionChange().x) * RESISTANCE)
                        .coerceIn(0f, maxTravel)
                    scope.launch { offset.snapTo(next) }
                    if (!armed && next >= threshold) {
                        armed = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else if (armed && next < threshold) {
                        armed = false
                    }
                    change.consume()
                }
                if (armed) reply()
                scope.launch { offset.animateTo(0f, spring()) }
            }
        },
    ) {
        val progress = (offset.value / threshold).coerceIn(0f, 1f)
        if (progress > 0f) {
            VmIcon(
                imageVector = Icons.AutoMirrored.Outlined.Reply,
                contentDescription = null,
                tint = VmTheme.colors.iconSecondary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = VmSpacing.lg)
                    .alpha(progress),
            )
        }
        // offset is layout-relative: a positive x moves toward the end in either direction.
        Box(modifier = Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) {
            content()
        }
    }
}

/** An attachment still arriving has no message row yet, so its progress gets a placeholder. */
@Composable
private fun IncomingTransferBubble(progress: AttachmentProgress?, modifier: Modifier = Modifier) {
    MessageBubble(direction = BubbleDirection.Incoming, modifier = modifier) {
        ProgressPill(
            label = stringResource(R.string.feature_chat_receiving),
            progress = progress?.fraction,
        )
    }
}
