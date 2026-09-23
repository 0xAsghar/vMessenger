package ir.vmessenger.core.designsystem.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import ir.vmessenger.core.designsystem.theme.VmDefaultTypography

/**
 * The colour text and icons take when they are not told one.
 *
 * Set by whatever they sit on — a filled button sets it to the colour that reads on its fill, a
 * surface to the colour that reads on its background — so a label or icon placed inside does the
 * right thing without being told what it is inside. This is the job Material's `LocalContentColor`
 * did; it is ours now so nothing depends on Material 3 for it.
 */
val LocalVmContentColor = compositionLocalOf { Color.Black }

/** The style text takes when it is not told one; narrowed by containers, as with the colour. */
val LocalVmTextStyle = compositionLocalOf { VmDefaultTypography.bodyMd }

/** Provides [color] as the content colour, and [style] merged over the current text style if given. */
@Composable
fun ProvideVmContent(
    color: Color,
    style: TextStyle? = null,
    content: @Composable () -> Unit,
) {
    val merged = style?.let { LocalVmTextStyle.current.merge(it) } ?: LocalVmTextStyle.current
    CompositionLocalProvider(
        LocalVmContentColor provides color,
        LocalVmTextStyle provides merged,
        content = content,
    )
}
