package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * Photos sent together, as one grid inside one bubble: the way every messenger draws an album.
 *
 * Rows of two or three, fullest at the bottom, so the eye lands on the photos rather than on a
 * lonely last one; a first row of one is the widest, and is where an odd photo out goes. Each tile
 * opens and long-presses on its own — the grid is only a way of drawing several messages.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumGridContent(
    tiles: List<AlbumTile>,
    onOpen: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        modifier = modifier
            .fillMaxWidth()
            .clip(VmShapes.media),
    ) {
        var start = 0
        for (size in albumRows(tiles.size)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight(size)),
            ) {
                for (index in start until start + size) {
                    val tile = tiles[index]
                    AlbumTileView(
                        tile = tile,
                        description = stringResource(
                            R.string.vm_album_image,
                            VmDateFormat.number(index + 1),
                            VmDateFormat.number(tiles.size),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .combinedClickable(onClick = { onOpen(index) }, onLongClick = { onLongPress(index) }),
                    )
                }
            }
            start += size
        }
    }
}

@Composable
private fun AlbumTileView(tile: AlbumTile, description: String, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.background(VmTheme.colors.bgSubtleStrong)) {
        if (tile.model != null) {
            AsyncImage(
                model = tile.model,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        tile.progress?.let { ProgressPill(label = VmTextFormat.percent(it), progress = it) }
        if (tile.failed) {
            VmIcon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.vm_tick_failed),
                tint = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(VmTheme.colors.bgCritical)
                    .padding(VmSpacing.xxs),
            )
        }
    }
}

/**
 * How many photos go on each row, top to bottom: two and three are pairs and triples, four is
 * two pairs, and past that rows of three with whatever is left over — one or two — on top.
 */
fun albumRows(count: Int): List<Int> = when {
    count <= 2 -> listOf(count)
    count == 3 -> listOf(1, 2)
    count == 4 -> listOf(2, 2)
    else -> {
        val lead = count % PER_ROW
        val full = List(count / PER_ROW) { PER_ROW }
        if (lead == 0) full else listOf(lead) + full
    }
}

/** Fewer photos to a row, taller the row: a photo on its own should not be a strip. */
private fun rowHeight(size: Int): Dp = when (size) {
    1 -> 200.dp
    2 -> 150.dp
    else -> 110.dp
}

private const val PER_ROW = 3
private val TILE_GAP = 2.dp
