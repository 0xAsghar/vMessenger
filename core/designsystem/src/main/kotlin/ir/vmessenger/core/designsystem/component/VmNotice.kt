package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/** A boxed message inside a screen: something to know, to be careful about, or that went wrong. */
@Composable
fun VmNotice(
    text: String,
    modifier: Modifier = Modifier,
    kind: VmNoticeKind = VmNoticeKind.Info,
    title: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val c = VmTheme.colors
    val (background, tint, icon) = when (kind) {
        VmNoticeKind.Info -> Triple(c.bgInfoSubtle, c.textInfo, Icons.Outlined.Info)
        VmNoticeKind.Warning -> Triple(c.bgWarningSubtle, c.textWarning, Icons.Outlined.WarningAmber)
        VmNoticeKind.Critical -> Triple(c.bgCriticalSubtle, c.textCritical, Icons.Outlined.ErrorOutline)
    }
    Row(
        modifier = modifier.fillMaxWidth().background(background, VmShapes.card).padding(VmSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        VmIcon(imageVector = icon, contentDescription = null, tint = tint)
        Column(verticalArrangement = Arrangement.spacedBy(VmSpacing.xs), modifier = Modifier.weight(1f)) {
            if (title != null) VmText(text = title, style = VmTheme.typography.bodyMdMedium, color = tint)
            VmText(text = text, style = VmTheme.typography.bodyMd, color = c.textPrimary)
            action?.invoke()
        }
    }
}
