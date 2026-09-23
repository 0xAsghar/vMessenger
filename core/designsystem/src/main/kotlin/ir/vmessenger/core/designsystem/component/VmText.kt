package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor
import ir.vmessenger.core.designsystem.foundation.LocalVmTextStyle

/**
 * Text, in the style and colour of wherever it is placed unless told otherwise.
 *
 * [style] defaults to the surrounding text style and [color] to the surrounding content colour, so
 * a label inside a filled button is readable on the fill without the call site knowing what the fill
 * is. An explicit [color] wins over the style's own colour, which wins over the content colour.
 */
@Composable
@Suppress("LongParameterList") // Mirrors the platform text API: each parameter is an independent knob.
fun VmText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalVmTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = resolve(style, color, textAlign),
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}

/** [VmText] for styled runs — a highlighted search match, a bold name inside a sentence. */
@Composable
@Suppress("LongParameterList") // Mirrors the platform text API: each parameter is an independent knob.
fun VmText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalVmTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = resolve(style, color, textAlign),
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        inlineContent = inlineContent,
    )
}

@Composable
private fun resolve(style: TextStyle, color: Color, textAlign: TextAlign?): TextStyle {
    val resolvedColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> LocalVmContentColor.current
    }
    return style.merge(
        TextStyle(
            color = resolvedColor,
            textAlign = textAlign ?: TextAlign.Unspecified,
        ),
    )
}
