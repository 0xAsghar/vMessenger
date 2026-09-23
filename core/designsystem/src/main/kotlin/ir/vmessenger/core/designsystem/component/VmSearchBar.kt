package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The search field that takes the top bar's place while a list is being searched: a soft pill with
 * the way back at its start and a clear button once there is something to clear.
 *
 * It takes focus as it appears — opening search is asking to type — and the keyboard's search key
 * only puts the keyboard away, since the list already follows every keystroke. The caller owns the
 * query and decides when [onClose] leaves search mode.
 */
@Composable
fun VmSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.vm_search_placeholder),
) {
    val c = VmTheme.colors
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focus) { focus.requestFocus() }
    val style = VmTheme.typography.bodyLg.merge(TextStyle(color = c.textPrimary))
    // The bar keeps only a sliver at its end for the action buttons it usually holds; the pill takes
    // the rest back, so it sits as far from the end edge as from the start.
    VmSurface(
        shape = VmShapes.pill,
        color = c.bgSubtle,
        contentColor = c.iconSecondary,
        modifier = modifier.padding(end = END_INSET),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(HEIGHT),
        ) {
            VmIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.vm_search_close),
                onClick = onClose,
                touchSize = HEIGHT,
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(c.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            VmText(text = placeholder, style = style, color = c.textPlaceholder, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                VmIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.vm_search_clear),
                    onClick = { onQueryChange("") },
                    touchSize = HEIGHT,
                )
            }
        }
    }
}

private val HEIGHT = 44.dp
private val END_INSET = 12.dp
