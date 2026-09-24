package ir.vmessenger.feature.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ir.vmessenger.core.designsystem.component.AlbumGridContent
import ir.vmessenger.core.designsystem.component.AlbumTile
import ir.vmessenger.core.designsystem.component.BubbleDirection
import ir.vmessenger.core.designsystem.component.BubbleMeta
import ir.vmessenger.core.designsystem.component.MessageBubble
import ir.vmessenger.core.designsystem.component.MessageBubbleDefaults
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.domain.model.AttachmentProgress

/**
 * An album: its photos as one grid in one bubble, with one time and one set of ticks.
 *
 * Every photo is still its own message underneath, and the grid keeps it that way: a tap opens
 * that photo, a long-press offers what can be done to that photo, and a failure retries only the
 * photos that failed. The ticks are the slowest photo's, so "read" means every photo was.
 */
@Composable
internal fun AlbumBubbleItem(
    album: ChatItem.Album,
    progress: Map<String, AttachmentProgress>,
    actions: MessageActions,
    images: AttachmentImages,
    highlighted: Boolean,
) {
    val first = album.first
    val direction = if (first.outgoing) BubbleDirection.Outgoing else BubbleDirection.Incoming
    val base = MessageBubbleDefaults.colors(direction)
    val container by animateColorAsState(
        targetValue = if (highlighted) VmTheme.colors.bubbleHighlight else base.container,
        animationSpec = VmMotion.emphasis(),
        label = "album-highlight",
    )
    val ticks = album.ticks
    val ticksLabel = ticks?.let { ticksLabel(it) }
    val context = LocalContext.current
    // Keyed on the photos, not remembered per tile: a photo landing mid-album shifts every tile
    // after it, and a positional remember would then hand each tile its neighbour's image.
    val requests = remember(album.images, context) {
        album.images.map { image ->
            if (image.attachment?.available == true) images.request(context, image.messageId) else null
        }
    }
    val tiles = album.images.mapIndexed { index, image ->
        AlbumTile(model = requests[index], progress = progress[image.messageId]?.fraction, failed = image.failed)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        MessageBubble(
            direction = direction,
            colors = base.copy(container = container),
            modifier = Modifier.semantics { ticksLabel?.let { stateDescription = it } },
        ) {
            SenderLabel(name = first.senderName?.takeIf { album.startsSenderRun }, seed = first.senderSeed)
            AlbumGridContent(
                tiles = tiles,
                onOpen = { actions.onOpenImage(album.images[it].messageId) },
                onLongPress = { actions.onLongPress(album.images[it].messageId) },
            )
            BubbleMeta(
                edited = false,
                expiring = album.images.any { it.expiring },
                time = album.newest.time,
                modifier = Modifier.align(Alignment.End),
                ticks = ticks,
            )
        }
        val failed = album.images.filter { it.failed }
        if (failed.isNotEmpty()) {
            FailureLine(outgoing = first.outgoing, errorCode = failed.first().errorCode) {
                failed.forEach { actions.onRetry(it.messageId) }
            }
        }
    }
}
