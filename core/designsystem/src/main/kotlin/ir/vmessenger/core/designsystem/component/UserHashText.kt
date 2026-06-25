package ir.vmessenger.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import ir.vmessenger.core.designsystem.theme.UserHashTextStyle

@Composable
fun UserHashText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = UserHashTextStyle,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        color = MaterialTheme.colorScheme.onBackground,
    )
}
