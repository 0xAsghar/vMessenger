package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ir.vmessenger.core.designsystem.LocalAppObscured
import ir.vmessenger.core.designsystem.foundation.ProvideVmContent
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * Every dialog in the app is this one: a rounded card on a dimmed screen, a title, content, and a
 * row of buttons at the reading end.
 *
 * **Nothing above the lock**, enforced here so no dialog can forget it. A dialog lives in its own
 * window, so the lock overlay — a composable inside the app's window — does not cover it: a dialog
 * left open when the app locked stayed on top of the lock screen and stayed fully interactive, which
 * is a way past the lock rather than a cosmetic flaw. Not composing rather than dismissing, so it is
 * still there when the user comes back. The window inherits the activity's FLAG_SECURE.
 */
@Composable
fun VmDialog(
    onDismissRequest: () -> Unit,
    title: String?,
    buttons: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalAppObscured.current) return
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        VmSurface(
            shape = VmShapes.dialog,
            color = VmTheme.colors.bgElevated,
            contentColor = VmTheme.colors.textPrimary,
            shadowElevation = VmElevation.sheet,
            modifier = modifier
                .padding(horizontal = SCREEN_MARGIN)
                .widthIn(min = MIN_WIDTH, max = MAX_WIDTH),
        ) {
            Column(modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                if (title != null) {
                    VmText(
                        text = title,
                        style = VmTheme.typography.headingSm,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .semantics { heading() },
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(top = if (title != null) 12.dp else 0.dp),
                ) {
                    ProvideVmContent(color = VmTheme.colors.textSecondary, style = VmTheme.typography.bodyMd) {
                        content()
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, androidx.compose.ui.Alignment.End),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 16.dp),
                    content = buttons,
                )
            }
        }
    }
}

private val SCREEN_MARGIN = 24.dp
private val MIN_WIDTH = 280.dp
private val MAX_WIDTH = 560.dp
