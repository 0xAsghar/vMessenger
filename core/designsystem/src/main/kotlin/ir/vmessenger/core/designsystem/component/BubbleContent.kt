package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

private val MediaMinWidth = 200.dp
private val MediaMaxHeight = 280.dp
private val PlayIconSize = 48.dp
private val DurationShape = RoundedCornerShape(6.dp)

/** Over a photo, whatever the app's theme: dark glass and white on it. */
private val MediaScrim = Color.Black.copy(alpha = 0.55f)
private val OnMedia = Color.White

/** Plain text payload of a bubble. */
@Composable
fun TextBubbleContent(
    text: String,
    modifier: Modifier = Modifier,
) {
    VmText(
        text = text,
        style = VmTheme.typography.bodyLg,
        modifier = modifier,
    )
}

/** Image attachment with an optional caption and transfer progress. */
@Composable
fun ImageBubbleContent(
    model: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.vm_bubble_image),
    caption: String? = null,
    progress: Float? = null,
) {
    Column(modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .clip(VmShapes.media)
                    .widthIn(min = MediaMinWidth)
                    .heightIn(max = MediaMaxHeight),
            )
            ProgressOverlay(progress)
        }
        Caption(caption)
    }
}

/** Video attachment: thumbnail, play badge and baked-in duration. */
@Composable
fun VideoBubbleContent(
    model: Any?,
    durationMs: Long,
    modifier: Modifier = Modifier,
    caption: String? = null,
    progress: Float? = null,
) {
    Column(modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.vm_bubble_video),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .clip(VmShapes.media)
                    .widthIn(min = MediaMinWidth)
                    .heightIn(max = MediaMaxHeight),
            )
            if (progress == null) {
                VmIcon(
                    imageVector = Icons.Outlined.PlayCircleOutline,
                    contentDescription = stringResource(R.string.vm_bubble_video_play),
                    tint = OnMedia,
                    size = PlayIconSize,
                )
            }
            ProgressOverlay(progress)
            DurationBadge(durationMs, Modifier.align(Alignment.BottomStart))
        }
        Caption(caption)
    }
}

/** Generic file attachment row: icon, name and Persian size. */
@Composable
fun FileBubbleContent(
    name: String,
    sizeBytes: Long,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .then(clickable)
            .widthIn(min = MediaMinWidth)
            .padding(vertical = VmSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        VmIcon(
            imageVector = Icons.Outlined.InsertDriveFile,
            contentDescription = stringResource(R.string.vm_bubble_file),
            size = VmSizes.avatarSm,
        )
        Column(modifier = Modifier.weight(1f)) {
            VmText(
                text = name,
                style = VmTheme.typography.bodyMdMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            VmText(
                text = VmDateFormat.fileSize(sizeBytes),
                style = VmTheme.typography.bodySm,
                color = VmTheme.colors.textSecondary,
            )
        }
        if (progress != null) {
            ProgressPill(label = progressLabel(progress), progress = progress)
        }
    }
}

@Composable
private fun Caption(caption: String?) {
    if (caption.isNullOrBlank()) return
    VmText(
        text = caption,
        style = VmTheme.typography.bodyLg,
        modifier = Modifier.padding(top = VmSpacing.xs),
    )
}

@Composable
private fun ProgressOverlay(progress: Float?) {
    if (progress == null) return
    ProgressPill(label = progressLabel(progress), progress = progress)
}

@Composable
private fun DurationBadge(durationMs: Long, modifier: Modifier = Modifier) {
    if (durationMs <= 0L) return
    Box(
        modifier = modifier
            .padding(VmSpacing.xs)
            .clip(DurationShape)
            .background(MediaScrim)
            .padding(horizontal = VmSpacing.xs),
    ) {
        VmText(
            text = VmDateFormat.duration(durationMs),
            style = VmTheme.typography.bodyXsMedium,
            color = OnMedia,
        )
    }
}

private fun progressLabel(progress: Float): String = VmTextFormat.percent(progress)
