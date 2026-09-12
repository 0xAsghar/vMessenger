package ir.vmessenger.core.designsystem.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.designsystem.error.toUiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * A one-shot message a ViewModel wants shown in a snackbar. It carries a resource id rather than
 * a rendered string so that nothing in the ViewModel layer has to touch a `Context` — this is
 * what replaces the app's `Toast` calls.
 */
@Immutable
sealed interface UiMessage {

    /** A Persian string resource with optional format arguments. */
    @Immutable
    data class Text(
        @StringRes val resId: Int,
        val args: ImmutableList<Any> = persistentListOf(),
    ) : UiMessage

    /** A failure; the text comes from `AppError.toUiText()`. */
    @Immutable
    data class Failure(val error: AppError) : UiMessage
}

/** Renders the message. Call from composition, then hand the result to the snackbar host. */
// stringResource takes vararg formatArgs, so the spread is unavoidable; the arrays are 0-2 long.
@Suppress("SpreadOperator")
@Composable
fun UiMessage.asText(): String = when (this) {
    is UiMessage.Text -> stringResource(resId, *args.toTypedArray())
    is UiMessage.Failure -> error.toUiText()
}
