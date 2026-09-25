package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * Text that is code: commands, URLs, fingerprints, logs. Monospace and left to right in either
 * language, scrolls sideways rather than wrapping, and can be copied when [onCopy] is given.
 */
@Composable
fun VmCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    onCopy: (() -> Unit)? = null
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier.fillMaxWidth().background(
                VmTheme.colors.bgSubtle,
                VmShapes.field
            ).padding(VmSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            VmText(
                text = text,
                style = VmTheme.typography.bodySm.copy(fontFamily = FontFamily.Monospace),
                color = VmTheme.colors.textPrimary,
                maxLines = maxLines,
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            )
            if (onCopy != null) {
                VmIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.vm_copy),
                    onClick = onCopy
                )
            }
        }
    }
}
