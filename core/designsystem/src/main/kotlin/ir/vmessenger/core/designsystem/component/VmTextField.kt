package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.foundation.ProvideVmContent
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The text field: its label above it, a softly rounded outline, help or an error below.
 *
 * Element X's shape rather than Material's: the label is plain text sitting over the box, not a
 * floating label that shrinks into the border, and focus is shown by a stronger edge. The screen
 * owns the text; everything static about the field travels in [config]. [textStyle] is merged over
 * the default, which is how the user-ID field gets its monospace, left-to-right face.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // Slots and knobs of one field; the layout reads top to bottom.
fun VmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    config: VmTextFieldConfig = VmTextFieldConfig(),
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle? = null,
) {
    val c = VmTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge by animateColorAsState(
        targetValue = edgeColor(config, focused),
        animationSpec = VmMotion.emphasis(),
        label = "edge",
    )
    val edgeWidth = if (focused || config.isError) FOCUSED_EDGE else EDGE
    val baseStyle = VmTheme.typography.bodyLg.merge(
        TextStyle(color = if (config.enabled) c.textPrimary else c.textDisabled),
    )
    val style = textStyle?.let { baseStyle.merge(it) } ?: baseStyle

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
        if (config.label != null) {
            VmText(
                text = config.label,
                style = VmTheme.typography.bodyMdMedium,
                color = if (config.isError) c.textCritical else c.textSecondary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = config.enabled,
            readOnly = config.readOnly,
            textStyle = style,
            keyboardOptions = KeyboardOptions(
                capitalization = config.capitalization,
                keyboardType = config.keyboardType,
                imeAction = config.imeAction,
            ),
            keyboardActions = keyboardActions,
            singleLine = config.singleLine,
            minLines = config.minLines,
            maxLines = config.maxLines,
            visualTransformation = if (config.isPassword) {
                PasswordVisualTransformation()
            } else {
                config.visualTransformation
            },
            interactionSource = interaction,
            cursorBrush = SolidColor(c.textPrimary),
            decorationBox = { inner ->
                FieldFrame(edge = edge, edgeWidth = edgeWidth, leadingIcon = leadingIcon, trailingIcon = trailingIcon) {
                    if (value.isEmpty() && config.placeholder != null) {
                        VmText(text = config.placeholder, style = style, color = c.textPlaceholder, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (config.supportingText != null) {
            VmText(
                text = config.supportingText,
                style = VmTheme.typography.bodySm,
                color = supportingColor(config),
                modifier = Modifier.padding(horizontal = SUPPORTING_INSET),
            )
        }
    }
}

@Composable
@ReadOnlyComposable
private fun edgeColor(config: VmTextFieldConfig, focused: Boolean): Color {
    val c = VmTheme.colors
    return when {
        config.isError -> c.borderCritical
        !config.enabled -> c.borderSubtle
        focused -> c.borderFocused
        else -> c.borderInteractive
    }
}

@Composable
@ReadOnlyComposable
private fun supportingColor(config: VmTextFieldConfig): Color {
    val c = VmTheme.colors
    return when {
        config.isError -> c.textCritical
        config.supportingIsSuccess -> c.textSuccess
        else -> c.textSecondary
    }
}

@Composable
private fun FieldFrame(
    edge: Color,
    edgeWidth: Dp,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    field: @Composable () -> Unit,
) {
    ProvideVmContent(color = VmTheme.colors.iconSecondary) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ICON_GAP),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MIN_HEIGHT)
                .border(edgeWidth, edge, VmShapes.field)
                .padding(
                    start = if (leadingIcon != null) ICON_INSET else TEXT_INSET,
                    end = if (trailingIcon != null) ICON_INSET else TEXT_INSET,
                ),
        ) {
            leadingIcon?.invoke()
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = TEXT_VERTICAL),
            ) { field() }
            trailingIcon?.invoke()
        }
    }
}

private val MIN_HEIGHT = 52.dp
private val EDGE = 1.dp
private val FOCUSED_EDGE = 2.dp
private val TEXT_INSET = 16.dp
private val ICON_INSET = 4.dp
private val ICON_GAP = 4.dp
private val TEXT_VERTICAL = 13.dp
private val LABEL_GAP = 8.dp
private val SUPPORTING_INSET = 4.dp
