package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun QrCard(
    payload: String,
    userHash: String,
    modifier: Modifier = Modifier,
    // A pairing descriptor at level H needs 79-87 module columns, quiet zone included. At 256.dp
    // that is 9 device pixels a module on a 3x screen and 6 on a 2x one, which is where a dotted
    // code still reads; 220.dp left the same grid at 7 and 5.
    qrSize: Dp = 256.dp,
    showShareActions: Boolean = true,
) {
    Column(
        // Was 24.dp at the sides; the wider code needs that back to clear a 360.dp screen edge.
        modifier = modifier.padding(VmSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StyledQrCode(
            payload = payload,
            modifier = Modifier.padding(vertical = VmSpacing.sm),
            size = qrSize,
            style = QrStyle.Branded,
        )
        UserHashLabel(modifier = Modifier.padding(top = VmSpacing.lg))
        UserHashText(
            text = userHash,
            modifier = Modifier.padding(top = VmSpacing.sm, start = VmSpacing.sm, end = VmSpacing.sm),
        )
        if (showShareActions) {
            UserHashShareRow(userHash = userHash)
        }
    }
}

@Composable
fun StyledQrCode(
    payload: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    style: QrStyle = QrStyle.Plain,
) {
    BoxWithConstraints(modifier = modifier) {
        // Shrink to what the parent actually offers instead of overflowing: a dialog on a narrow
        // screen would otherwise clip a finder pattern, and a clipped finder pattern never scans.
        val available = (maxWidth - VmSpacing.md * 2).coerceAtLeast(VmSizes.touchTarget)
        val side = size.coerceAtMost(available)
        val maxPixelSize = with(LocalDensity.current) { side.roundToPx().coerceAtLeast(1) }
        val render = produceState<QrRender?>(initialValue = null, payload, maxPixelSize, style) {
            value = withContext(Dispatchers.Default) {
                runCatching { rasterizeQr(payload, maxPixelSize, style) }.getOrNull()
            }
        }.value

        Surface(shape = MaterialTheme.shapes.large, color = style.backgroundColor) {
            Box(
                modifier = Modifier
                    .padding(VmSpacing.md)
                    .size(side),
                contentAlignment = Alignment.Center,
            ) {
                if (render == null) CircularProgressIndicator() else QrImage(render, style)
            }
        }
    }
}

@Composable
private fun QrImage(render: QrRender, style: QrStyle) {
    // Drawn at the bitmap's own pixel count, which the rasterizer rounded down to a whole number
    // of pixels per module: at any other size the modules would be resampled into each other.
    val side = with(LocalDensity.current) { render.sidePx.toDp() }
    Image(
        bitmap = render.bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(side),
        filterQuality = FilterQuality.None,
    )
    if (render.logoSideFraction > 0f) {
        Image(
            painter = painterResource(R.drawable.ic_vmessenger_logo),
            contentDescription = null,
            modifier = Modifier.size(side * render.logoSideFraction),
            // drawable-night carries the same mark in white, and painterResource would pick it
            // under a dark system theme — invisible on a plate that is always light. The tint,
            // not the resource qualifier, decides the colour here.
            colorFilter = ColorFilter.tint(style.moduleColor),
        )
    }
}
