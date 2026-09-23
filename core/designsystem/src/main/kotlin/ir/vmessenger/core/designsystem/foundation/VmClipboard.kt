package ir.vmessenger.core.designsystem.foundation

import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import ir.vmessenger.core.designsystem.R

/**
 * Whether a copy needs the app to say it happened. From Android 13 the system previews whatever
 * lands on the clipboard, and a confirmation of ours on top of that says the same thing twice;
 * below 13 nothing else says it at all.
 */
val copyNeedsConfirmation: Boolean
    get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

/**
 * Copies text, confirming it with [confirmation] only where the system does not — see
 * [copyNeedsConfirmation]. The confirmation is a toast: copy buttons sit in dialogs, sheets and
 * screens without a snackbar host, and a toast outlives whichever of them closes on the copy.
 */
@Composable
fun rememberCopyToClipboard(confirmation: String = stringResource(R.string.vm_copied)): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context, confirmation) {
        fun(text: String) {
            clipboard.setText(AnnotatedString(text))
            if (copyNeedsConfirmation) Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
        }
    }
}
