package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.foundation.ProvideVmContent
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The one row shape the app uses for a list of people or conversations: avatar, a title that
 * truncates, an optional second line and whatever belongs at the end.
 *
 * The second line and the end slot are set in the quiet secondary style unless they say otherwise.
 * Background, press feedback and click handling stay with the caller through [modifier] — a chat
 * row and a contact row want different gestures out of the same geometry.
 */
@Composable
fun VmListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    avatar: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = VmSizes.listItemHeight)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        avatar()
        Column(modifier = Modifier.weight(1f)) {
            VmText(
                text = title,
                style = VmTheme.typography.bodyLgMedium,
                color = VmTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                ProvideVmContent(color = VmTheme.colors.textSecondary, style = VmTheme.typography.bodyMd) {
                    subtitle()
                }
            }
        }
        if (trailing != null) {
            ProvideVmContent(color = VmTheme.colors.textSecondary, style = VmTheme.typography.bodySm) {
                trailing()
            }
        }
    }
}
