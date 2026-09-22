package ir.vmessenger.core.designsystem.component

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.vmessenger.core.designsystem.theme.VmSizes

/** The one spinner: primary-coloured, at a fixed stroke, shown while a screen or action resolves. */
@Composable
fun VmProgressIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = VmSizes.progressStroke,
    )
}
