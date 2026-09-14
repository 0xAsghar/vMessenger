package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * The app's one bottom sheet: a title in the same place at the same weight, and a bottom gutter
 * that is the navigation bar's own inset.
 *
 * A fixed bottom padding was the previous convention and it is wrong twice over — too small
 * behind a three-button bar, too large under a gesture pill. The inset is the only value that
 * is right on both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = VmSpacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
            )
            content()
        }
    }
}
