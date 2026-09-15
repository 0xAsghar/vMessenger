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
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.LocalAppObscured
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
    // Nothing above the lock. A dialog and a modal sheet each live in their own window, so the
    // lock overlay — which is a composable inside the app's own window — does not cover them: a
    // sheet left open when the app locked stayed on top of the lock screen and stayed fully
    // interactive, which is a way past a lock rather than a cosmetic flaw. Not composing rather
    // than dismissing, so it is still there when the user comes back.
    if (LocalAppObscured.current) return
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
            // Blank means no header at all rather than an empty band: a sheet opened on a voice
            // message has nothing to quote. One line always — a title here can be a message
            // preview, and the actions are what the sheet is for.
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
                )
            }
            content()
        }
    }
}
