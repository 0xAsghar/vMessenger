package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The primary action: filled in the text colour itself — near-black on light, near-white on dark —
 * which is how Element X marks the one thing a screen is for. [destructive] swaps it for red, for an
 * action that cannot be taken back. [loading] keeps the button's width and replaces the label with
 * a spinner, so a slow action does not make the layout jump or accept a second tap.
 */
@Composable
@Suppress("LongParameterList") // One parameter per independent property of the button.
fun VmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    destructive: Boolean = false,
    loading: Boolean = false,
    size: VmButtonSize = VmButtonSize.Large,
) {
    val c = VmTheme.colors
    val colors = when {
        !enabled -> ButtonColors(c.bgSubtleStrong, c.textDisabled)
        destructive -> ButtonColors(c.bgCritical, c.textOnSolid)
        else -> ButtonColors(c.bgActionPrimary, c.textOnActionPrimary)
    }
    VmButtonFrame(
        spec = ButtonSpec(text, leadingIcon, size, colors, border = null),
        onClick = onClick,
        enabled = enabled && !loading,
        loading = loading,
        modifier = modifier,
    )
}

/** The secondary of a pair: an outline, no fill. */
@Composable
@Suppress("LongParameterList") // One parameter per independent property of the button.
fun VmOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    destructive: Boolean = false,
    loading: Boolean = false,
    size: VmButtonSize = VmButtonSize.Large,
) {
    val c = VmTheme.colors
    val content = when {
        !enabled -> c.textDisabled
        destructive -> c.textCritical
        else -> c.textPrimary
    }
    val edge = when {
        !enabled -> c.borderSubtle
        destructive -> c.borderCritical
        else -> c.borderInteractive
    }
    VmButtonFrame(
        spec = ButtonSpec(text, leadingIcon, size, ButtonColors(Color.Transparent, content), BorderStroke(1.dp, edge)),
        onClick = onClick,
        enabled = enabled && !loading,
        loading = loading,
        modifier = modifier,
    )
}

/** The lowest emphasis: no container at all. For a dismissive choice, or an action inside a dialog. */
@Composable
@Suppress("LongParameterList") // One parameter per independent property of the button.
fun VmTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    destructive: Boolean = false,
    size: VmButtonSize = VmButtonSize.Medium,
) {
    val c = VmTheme.colors
    val content = when {
        !enabled -> c.textDisabled
        destructive -> c.textCritical
        else -> c.textPrimary
    }
    VmButtonFrame(
        spec = ButtonSpec(text, leadingIcon, size, ButtonColors(Color.Transparent, content), border = null),
        onClick = onClick,
        enabled = enabled,
        loading = false,
        modifier = modifier,
    )
}

@Immutable
private data class ButtonColors(val container: Color, val content: Color)

@Immutable
private data class ButtonSpec(
    val text: String,
    val leadingIcon: ImageVector?,
    val size: VmButtonSize,
    val colors: ButtonColors,
    val border: BorderStroke?,
)

@Composable
private fun VmButtonFrame(
    spec: ButtonSpec,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
    modifier: Modifier,
) {
    VmSurface(
        onClick = onClick,
        enabled = enabled,
        shape = VmShapes.pill,
        color = spec.colors.container,
        contentColor = spec.colors.content,
        border = spec.border,
        modifier = modifier.defaultMinSize(minWidth = MIN_WIDTH, minHeight = spec.size.height),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(PaddingValues(horizontal = spec.size.horizontalPadding)),
        ) {
            // The label stays laid out, invisibly, while loading: that is what keeps the width.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.alpha(if (loading) 0f else 1f),
            ) {
                if (spec.leadingIcon != null) {
                    VmIcon(imageVector = spec.leadingIcon, contentDescription = null, size = spec.size.iconSize)
                    Spacer(Modifier.width(ICON_GAP))
                }
                VmText(
                    text = spec.text,
                    style = labelStyle(spec.size),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (loading) VmProgressIndicator(size = spec.size.iconSize, color = spec.colors.content)
        }
    }
}

@Composable
private fun labelStyle(size: VmButtonSize): TextStyle = when (size) {
    VmButtonSize.Large -> VmTheme.typography.bodyLgMedium
    VmButtonSize.Medium -> VmTheme.typography.bodyMdMedium
}

private val MIN_WIDTH = 64.dp
private val ICON_GAP = 8.dp
