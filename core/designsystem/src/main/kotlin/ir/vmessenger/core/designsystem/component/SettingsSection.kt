package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * A group of settings under its name. Flat, edge to edge, no card around it: the header and the
 * space above it are what separate one group from the next, as in Element X, so a screen is a
 * single calm list rather than a stack of boxes. The rows bring their own side padding; the screen
 * around a section must not add any.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        content()
    }
}

/** Between two rows of one section: a hairline set in from both edges, fainter than the text. */
@Composable
fun SettingsDivider() {
    VmDivider(modifier = Modifier.padding(horizontal = VmSpacing.lg))
}
