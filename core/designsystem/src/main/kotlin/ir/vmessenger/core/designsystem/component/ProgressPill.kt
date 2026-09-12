package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmSpacing

private val IndicatorSize = 16.dp
private val IndicatorStroke = 2.dp
private const val SCRIM_ALPHA = 0.72f

/**
 * Transfer progress overlaid on a media bubble. A null [progress] renders the indeterminate
 * spinner used while an attachment is still being negotiated.
 */
@Composable
fun ProgressPill(
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = SCRIM_ALPHA),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
            modifier = Modifier.padding(horizontal = VmSpacing.md, vertical = VmSpacing.sm),
        ) {
            if (progress == null) {
                CircularProgressIndicator(
                    strokeWidth = IndicatorStroke,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(IndicatorSize),
                )
            } else {
                CircularProgressIndicator(
                    progress = { progress },
                    strokeWidth = IndicatorStroke,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(IndicatorSize),
                )
            }
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
