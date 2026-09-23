package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The name of a group of rows ("حریم خصوصی", "درخواست‌ها"): quiet, medium weight, in line with
 * the rows' own text. A heading to a screen reader, so the groups can be jumped between.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    VmText(
        text = title,
        style = VmTheme.typography.bodyMdMedium,
        color = VmTheme.colors.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = VmSpacing.lg, end = VmSpacing.lg, top = VmSpacing.lg, bottom = VmSpacing.xs)
            .semantics { heading() },
    )
}
