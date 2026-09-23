package ir.vmessenger.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow

/**
 * Shows every [UiMessage] a ViewModel emits in the screen's snackbar.
 *
 * The message is held in composition for one pass because `asText()` resolves a string resource
 * and can only run there — a ViewModel never touches a `Context`.
 */
@Composable
fun UiMessageSnackbarEffect(
    messages: Flow<UiMessage>,
    hostState: VmSnackbarHostState,
) {
    var pending by remember { mutableStateOf<UiMessage?>(null) }
    LaunchedEffect(messages) {
        messages.collect { pending = it }
    }
    val text = pending?.asText()
    LaunchedEffect(text) {
        if (text != null) {
            hostState.showSnackbar(text)
            pending = null
        }
    }
}
