package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * The design system's text field. The screen passes the mutable text directly and everything else
 * through [VmTextFieldConfig], which keeps the parameter list in bounds and the look consistent.
 */
@Composable
fun VmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    config: VmTextFieldConfig = VmTextFieldConfig(),
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = config.enabled,
        isError = config.isError,
        singleLine = config.singleLine,
        label = config.label?.let { text -> { Text(text = text) } },
        placeholder = config.placeholder?.let { text -> { Text(text = text) } },
        supportingText = config.supportingText?.let { text -> { Text(text = text) } },
        trailingIcon = trailingIcon,
        visualTransformation = passwordOrNone(config.isPassword),
        keyboardOptions = KeyboardOptions(keyboardType = config.keyboardType, imeAction = config.imeAction),
    )
}

private fun passwordOrNone(isPassword: Boolean): VisualTransformation =
    if (isPassword) PasswordVisualTransformation() else VisualTransformation.None
