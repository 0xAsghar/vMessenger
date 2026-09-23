package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's one screen frame: [VmTopBar] over [VmScaffold].
 *
 * [title] and [subtitle] cover ordinary screens; the conversation header passes [titleContent]
 * instead, which replaces the whole title slot (avatar + name + presence line). [scrolled] shows the
 * hairline under the bar — pass whether the content has scrolled away from its top, e.g.
 * `listState.canScrollBackward`. Everything else is optional.
 */
@Suppress("LongParameterList") // Compose slot API: one parameter per optional region of the frame.
@Composable
fun VMessengerScaffold(
    title: String,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    scrolled: Boolean = false,
    snackbarHost: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    VmScaffold(
        modifier = modifier,
        topBar = {
            VmTopBar(
                title = { titleContent?.invoke() ?: VmTopBarTitle(title = title, subtitle = subtitle) },
                navigationIcon = onNavigateBack?.let { back -> { VmBackButton(onClick = back) } },
                actions = actions,
                showDivider = scrolled,
            )
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = contentWindowInsets ?: WindowInsets.systemBars,
        content = content,
    )
}
