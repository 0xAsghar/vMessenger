package ir.vmessenger.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import ir.vmessenger.core.designsystem.theme.UserHashTextStyle
import ir.vmessenger.core.designsystem.theme.VmTheme

@Composable
fun UserHashText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = UserHashTextStyle,
    textAlign: TextAlign = TextAlign.Center,
) {
    VmText(
        // Break at the group separators, not inside a group. The hash has no spaces, so when it
        // does not fit the line breaker falls back to breaking between characters — on the pairing
        // screen that stranded the final "J" of …-WV5J alone on a second line, which reads like a
        // truncated identifier rather than a wrapped one. A zero-width space after each separator
        // gives it somewhere legitimate to break; it is invisible, and it is not copied, because
        // the copy action works from the original string.
        text = remember(text) { text.replace("-", "-$GROUP_BREAK") },
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        color = VmTheme.colors.textPrimary,
    )
}

/** Invisible, and a legitimate place to break a line. */
private const val GROUP_BREAK = '\u200B'

/**
 * [UserHashText]'s break hints for a text field: a user ID being typed or pasted wraps between its
 * groups rather than inside one. Display only — the field still holds exactly what was entered.
 */
object UserHashLineBreaks : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = StringBuilder(raw.length + raw.count { it == '-' })
        val toShown = IntArray(raw.length + 1)
        for (i in raw.indices) {
            toShown[i] = shown.length
            shown.append(raw[i])
            if (raw[i] == '-') shown.append(GROUP_BREAK)
        }
        toShown[raw.length] = shown.length
        // Both sides of an inserted break map to the offset just after its dash.
        val toRaw = IntArray(shown.length + 1)
        for (i in raw.indices) {
            toRaw[toShown[i]] = i
            if (raw[i] == '-') toRaw[toShown[i] + 1] = i + 1
        }
        toRaw[shown.length] = raw.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = toShown[offset.coerceIn(0, raw.length)]

            override fun transformedToOriginal(offset: Int): Int = toRaw[offset.coerceIn(0, shown.length)]
        }
        return TransformedText(AnnotatedString(shown.toString()), mapping)
    }
}
