package ir.vmessenger.ui.placeholder

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.R
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.VMessengerScaffold

/**
 * Body for a route that is defined in [ir.vmessenger.navigation.VmRoute] but whose
 * screen belongs to a later milestone (groups, image viewer, backup UI, updater,
 * blocked contacts, contact detail). The route exists so navigation, deep links and
 * back-stack behaviour are settled now; a later phase replaces the body only.
 *
 * [fullBleed] is the inset contract for screens that draw behind the system bars
 * (image viewer, map): the scaffold consumes nothing and the content pads itself
 * with `safeDrawingPadding()`.
 */
@Composable
fun ComingSoonRoute(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    fullBleed: Boolean = false,
) {
    VMessengerScaffold(
        title = title,
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        contentWindowInsets = if (fullBleed) WindowInsets(0) else null,
    ) { padding ->
        EmptyState(
            icon = Icons.Outlined.Construction,
            title = stringResource(R.string.coming_soon_title),
            body = stringResource(R.string.coming_soon_body),
            modifier = if (fullBleed) {
                Modifier.safeDrawingPadding()
            } else {
                Modifier.padding(padding)
            },
        )
    }
}
