package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.vmessenger.core.designsystem.theme.VmSpacing

/** Small accented caption above a group of rows ("حریم خصوصی", "درخواست‌ها"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = VmSpacing.lg,
                end = VmSpacing.lg,
                top = VmSpacing.lg,
                bottom = VmSpacing.sm,
            ),
    )
}
