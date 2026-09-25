package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * A field for a secret — a server password, a key's passphrase. Its text lives in [state], which
 * the caller owns and clears; it is never saved with the screen's state (no `rememberSaveable`),
 * never shown in the clear, and the keyboard is told not to learn it.
 */
@Composable
fun VmSecretField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String? = null,
    supportingText: String? = null,
    isError: Boolean = false
) {
    val c = VmTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            VmText(
                text = label,
                style = VmTheme.typography.bodyMdMedium,
                color = if (isError) c.textCritical else c.textSecondary
            )
        }
        BasicSecureTextField(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            textStyle = VmTheme.typography.bodyLg.merge(TextStyle(color = c.textPrimary)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            textObfuscationMode = TextObfuscationMode.Hidden,
            cursorBrush = SolidColor(c.textPrimary),
            decorator = { inner ->
                FieldFrame(
                    edge = if (isError) c.borderCritical else c.borderInteractive,
                    edgeWidth = if (isError) 2.dp else 1.dp,
                    leadingIcon = null,
                    trailingIcon = null,
                ) { inner() }
            },
        )
        if (supportingText != null) {
            VmText(
                text = supportingText,
                style = VmTheme.typography.bodySm,
                color = if (isError) c.textCritical else c.textSecondary
            )
        }
    }
}
