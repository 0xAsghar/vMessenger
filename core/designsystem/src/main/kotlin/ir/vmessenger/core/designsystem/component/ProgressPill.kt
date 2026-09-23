package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/** Dark enough to read white on over any photo, light enough to still see the photo through it. */
private val Scrim = Color.Black.copy(alpha = 0.6f)
private val OnScrim = Color.White
private val OnScrimTrack = Color.White.copy(alpha = 0.3f)

/**
 * Transfer progress laid over a media bubble. A null [progress] shows the spinner used while an
 * attachment is still being negotiated.
 *
 * The same dark glass in both themes: it sits on a photo, not on the app, so the app's light and
 * dark have nothing to say about what is readable on it.
 */
@Composable
fun ProgressPill(
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    VmSurface(shape = VmShapes.pill, color = Scrim, contentColor = OnScrim, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
            modifier = Modifier.padding(horizontal = VmSpacing.md, vertical = VmSpacing.sm),
        ) {
            if (progress == null) {
                VmProgressIndicator(size = VmSizes.iconSm, color = OnScrim)
            } else {
                // A transfer reports once per chunk, so the raw value visibly steps; animating it
                // makes the same data read as a transfer rather than a counter.
                val animated by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = VmMotion.emphasis(),
                    label = "transfer-progress",
                )
                VmProgressRing(progress = animated, size = VmSizes.iconSm, color = OnScrim, trackColor = OnScrimTrack)
            }
            VmText(text = label, style = VmTheme.typography.bodySmMedium)
        }
    }
}
