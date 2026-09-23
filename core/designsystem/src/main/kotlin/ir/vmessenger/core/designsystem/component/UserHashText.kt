package ir.vmessenger.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
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
        text = remember(text) { text.replace("-", "-\u200B") },
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        color = VmTheme.colors.textPrimary,
    )
}
